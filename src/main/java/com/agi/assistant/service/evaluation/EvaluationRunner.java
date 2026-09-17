package com.agi.assistant.service.evaluation;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.entity.GoldenQuery;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.llm.ModelProviderRouter;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final OpenAIConfig openAIConfig;
    private final ModelProviderRouter modelProviderRouter;
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
            // 是否有查询的生成阶段失败：若有，则整个任务不能标记为成功
            boolean anyGenerationFailed = false;

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

                    // F2：真正调用 LLM 生成答案，而非拼接检索内容
                    String generatedAnswer;
                    boolean genFailed = false;
                    try {
                        generatedAnswer = generateAnswer(gq.getQuery(), contexts);
                        if (generatedAnswer == null || generatedAnswer.isBlank()) {
                            genFailed = true;
                            generatedAnswer = "【LLM 生成失败：返回内容为空】";
                        }
                    } catch (Exception e) {
                        genFailed = true;
                        log.error("LLM 生成答案失败，queryId=[{}]：{}", gq.getId(), e.getMessage(), e);
                        generatedAnswer = "【LLM 生成失败：" + (e.getMessage() != null ? e.getMessage() : "未知错误") + "】";
                    }
                    if (genFailed) {
                        anyGenerationFailed = true;
                    }

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

            // 生成阶段存在失败则整体标记为失败，避免把不完整的评测误报为成功
            task.setStatus(anyGenerationFailed
                    ? EvaluationStatus.FAILED.getCode()
                    : EvaluationStatus.COMPLETED.getCode());
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

    /**
     * 调用 LLM（非流式）基于检索上下文生成答案。
     * <p>
     * <b>走 {@link ModelProviderRouter}</b>（而非直接调 GLM）：这样评测的「生成答案」这一步
     * 可随 {@code llm.provider} 配置在 GLM / Ark 之间切换 —— 这正是「模型选型对比」这条能力
     * 的代码落点；同时 {@code ModelProviderRouter.activeProviderName()} 会被写入快照的
     * {@code meta.model}，让「哪次评测用了哪个模型」有据可查。
     * <p>
     * prompt 显式要求模型「只依据给定上下文回答，上下文不足就说不知道」，
     * 避免模型用自身知识编造，从而保证 faithfulness 评测反映真实生成质量。
     *
     * @param query    原始问题
     * @param contexts 检索到的上下文列表
     * @return 生成的答案；provider 不可用或调用失败时抛出异常（由调用方做降级处理）
     */
    private String generateAnswer(String query, List<String> contexts) {
        String contextBlock = String.join("\n---\n", contexts);
        String prompt = String.format("""
                你是一个严谨的 RAG 问答助手。请只依据下面给出的「参考资料」回答问题。
                如果参考资料中没有足够信息来回答该问题，请明确回答「根据提供的资料无法回答该问题」。
                不要编造参考资料之外的信息，也不要使用你自己的知识。

                ## 参考资料
                %s

                ## 问题
                %s

                请直接给出答案，不要添加额外解释或前缀。
                """, truncate(contextBlock, 6000), truncate(query, 1000));

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system",
                        "content", "你是一个严格依据给定上下文作答的问答助手。"),
                Map.of("role", "user", "content", prompt));

        return modelProviderRouter.chat(
                messages, openAIConfig.getTemperature(), openAIConfig.getMaxTokens());
    }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
