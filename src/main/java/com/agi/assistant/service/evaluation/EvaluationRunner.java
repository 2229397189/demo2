package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.entity.GoldenQuery;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationRunner {

    private final EvaluationTaskMapper evaluationTaskMapper;
    private final EvaluationResultMapper evaluationResultMapper;
    private final BenchmarkDataset benchmarkDataset;
    private final RetrievalEvaluator retrievalEvaluator;
    private final GenerationEvaluator generationEvaluator;
    private final HybridRetrievalService hybridRetrievalService;
    private final ObjectMapper objectMapper;

    @Async
    public void runEvaluation(Long taskId) {
        EvaluationTask task = evaluationTaskMapper.selectById(taskId);
        if (task == null) {
            log.error("Evaluation task not found: {}", taskId);
            return;
        }

        log.info("Starting evaluation run for task [{}]", taskId);

        try {
            task.setStatus(EvaluationStatus.RUNNING.getCode());
            task.setUpdatedAt(LocalDateTime.now());
            evaluationTaskMapper.updateById(task);

            List<GoldenQuery> goldenQueries = benchmarkDataset.loadDataset(task.getDatasetId());
            if (goldenQueries.isEmpty()) {
                log.warn("No golden queries found for dataset [{}]", task.getDatasetId());
                task.setStatus(EvaluationStatus.FAILED.getCode());
                task.setUpdatedAt(LocalDateTime.now());
                evaluationTaskMapper.updateById(task);
                return;
            }

            task.setTotalQueries(goldenQueries.size());
            evaluationTaskMapper.updateById(task);

            int completed = 0;

            for (GoldenQuery gq : goldenQueries) {
                try {
                    String strategy = task.getRetrievalStrategy() != null
                            ? task.getRetrievalStrategy() : "HYBRID";
                    long startTime = System.currentTimeMillis();
                    List<SearchResult> results = hybridRetrievalService.retrieve(
                            gq.getQuery(), strategy, 10);
                    long latencyMs = System.currentTimeMillis() - startTime;

                    List<String> retrievedDocIds = results.stream()
                            .map(SearchResult::getDocumentId)
                            .collect(Collectors.toList());

                    List<String> expectedDocIds = benchmarkDataset.deserializeDocIds(
                            gq.getRelevantDocIds());
                    RetrievalEvaluator.RetrievalMetrics retrievalMetrics =
                            retrievalEvaluator.evaluate(retrievedDocIds, expectedDocIds, 10);

                    List<String> contexts = results.stream()
                            .map(SearchResult::getContent)
                            .collect(Collectors.toList());
                    String generatedAnswer = String.join("\n", contexts.stream()
                            .limit(3).collect(Collectors.toList()));

                    GenerationEvaluator.GenerationMetrics generationMetrics =
                            generationEvaluator.evaluate(
                                    gq.getQuery(), generatedAnswer, contexts,
                                    gq.getExpectedAnswer());

                    EvaluationResult evalResult = new EvaluationResult();
                    evalResult.setTaskId(taskId);
                    evalResult.setQueryId(gq.getId());
                    evalResult.setQuery(gq.getQuery());
                    evalResult.setGeneratedAnswer(generatedAnswer);
                    evalResult.setExpectedAnswer(gq.getExpectedAnswer());
                    evalResult.setRetrievedDocIds(writeJson(retrievedDocIds));
                    evalResult.setRetrievalMetrics(writeJson(retrievalMetrics));
                    evalResult.setGenerationMetrics(writeJson(generationMetrics));
                    evalResult.setLatencyMs(latencyMs);
                    evalResult.setCreatedAt(LocalDateTime.now());

                    evaluationResultMapper.insert(evalResult);
                    completed++;

                    task.setCompletedQueries(completed);
                    task.setUpdatedAt(LocalDateTime.now());
                    evaluationTaskMapper.updateById(task);

                } catch (Exception e) {
                    log.error("Failed to evaluate query [{}]: {}", gq.getId(), e.getMessage(), e);
                }
            }

            task.setStatus(EvaluationStatus.COMPLETED.getCode());
            task.setUpdatedAt(LocalDateTime.now());
            evaluationTaskMapper.updateById(task);

            log.info("Evaluation task [{}] completed: {}/{} queries",
                    taskId, completed, goldenQueries.size());

        } catch (Exception e) {
            log.error("Evaluation task [{}] failed: {}", taskId, e.getMessage(), e);
            task.setStatus(EvaluationStatus.FAILED.getCode());
            task.setUpdatedAt(LocalDateTime.now());
            evaluationTaskMapper.updateById(task);
        }
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
