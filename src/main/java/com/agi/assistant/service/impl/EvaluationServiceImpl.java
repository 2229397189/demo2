package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.dto.EvaluationTaskRequest;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.EvaluationService;
import com.agi.assistant.service.evaluation.EvaluationRunner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EvaluationService implementation.
 * <p>
 * Creates evaluation tasks, runs them asynchronously against benchmark datasets,
 * and provides result comparison.
 */
@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class EvaluationServiceImpl implements EvaluationService {

    private final EvaluationTaskMapper evaluationTaskMapper;
    private final EvaluationResultMapper evaluationResultMapper;
    private final EvaluationRunner evaluationRunner;
    private final ObjectMapper objectMapper;

    // ----------------------------------------------------------------
    //  Task Management
    // ----------------------------------------------------------------

    @Override
    public EvaluationTask createTask(EvaluationTaskRequest request, Long userId) {
        EvaluationTask task = new EvaluationTask();
        task.setUserId(userId);
        task.setName(request.getName());
        task.setDatasetId(request.getDatasetId());
        task.setRetrievalStrategy(request.getRetrievalStrategy());
        task.setModelId(request.getModelId());
        task.setStatus(EvaluationStatus.PENDING.getCode());
        task.setTotalQueries(0);
        task.setCompletedQueries(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        evaluationTaskMapper.insert(task);
        log.info("Created evaluation task [{}] for user [{}]", task.getId(), userId);

        // 注意：创建任务不再自动触发执行，避免与「运行」按钮重复触发。
        // 执行统一由 POST /api/evaluation/tasks/{taskId}/run 显式触发。
        return task;
    }

    @Override
    public EvaluationTask runTask(Long taskId) {
        EvaluationTask task = evaluationTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("评测任务不存在: " + taskId);
        }
        // 避免并发重复触发：正在运行中则直接返回当前任务
        if (task.getStatus() != null && task.getStatus() == EvaluationStatus.RUNNING.getCode()) {
            log.warn("评测任务 [{}] 正在运行，跳过重复触发", taskId);
            return task;
        }
        // 同步置为 RUNNING，既提供即时反馈也作为并发锁，再异步执行
        task.setStatus(EvaluationStatus.RUNNING.getCode());
        task.setUpdatedAt(LocalDateTime.now());
        evaluationTaskMapper.updateById(task);

        // 通过独立的 EvaluationRunner bean 异步执行，避免自调用导致 @Async 失效
        evaluationRunner.runEvaluation(task.getId());

        // 重新读取最新状态（此时应已置为 RUNNING）后返回
        return evaluationTaskMapper.selectById(taskId);
    }

    @Override
    public List<EvaluationTask> listTasks(Long userId) {
        return evaluationTaskMapper.selectList(
                new LambdaQueryWrapper<EvaluationTask>()
                        .eq(EvaluationTask::getUserId, userId)
                        .orderByDesc(EvaluationTask::getCreatedAt));
    }

    @Override
    public List<EvaluationResult> getTaskResults(Long taskId) {
        return evaluationResultMapper.selectList(
                new LambdaQueryWrapper<EvaluationResult>()
                        .eq(EvaluationResult::getTaskId, taskId)
                        .orderByAsc(EvaluationResult::getId));
    }

    // ----------------------------------------------------------------
    //  Comparison
    // ----------------------------------------------------------------

    @Override
    public Map<String, Object> compareResults(Long taskAId, Long taskBId) {
        EvaluationTask taskA = evaluationTaskMapper.selectById(taskAId);
        EvaluationTask taskB = evaluationTaskMapper.selectById(taskBId);

        if (taskA == null || taskB == null) {
            throw new RuntimeException("Evaluation task not found");
        }

        List<EvaluationResult> resultsA = getTaskResults(taskAId);
        List<EvaluationResult> resultsB = getTaskResults(taskBId);

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("taskA", buildTaskSummary(taskA, resultsA));
        comparison.put("taskB", buildTaskSummary(taskB, resultsB));
        comparison.put("metricsComparison", buildMetricsComparison(resultsA, resultsB));

        return comparison;
    }

    // ----------------------------------------------------------------
    //  Internal Methods
    // ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildTaskSummary(EvaluationTask task,
                                                  List<EvaluationResult> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", task.getId());
        summary.put("name", task.getName());
        summary.put("retrievalStrategy", task.getRetrievalStrategy());
        summary.put("modelId", task.getModelId());
        summary.put("status", EvaluationStatus.fromCode(task.getStatus()).name());
        summary.put("totalQueries", task.getTotalQueries());
        summary.put("completedQueries", task.getCompletedQueries());

        if (!results.isEmpty()) {
            // Compute average metrics
            double avgLatency = results.stream()
                    .mapToLong(r -> r.getLatencyMs() != null ? r.getLatencyMs() : 0)
                    .average().orElse(0.0);
            summary.put("averageLatencyMs", Math.round(avgLatency));
            summary.put("resultCount", results.size());
        }

        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMetricsComparison(List<EvaluationResult> resultsA,
                                                        List<EvaluationResult> resultsB) {
        Map<String, Object> comparison = new LinkedHashMap<>();

        // Average retrieval metrics
        Map<String, Double> avgA = computeAverageRetrievalMetrics(resultsA);
        Map<String, Double> avgB = computeAverageRetrievalMetrics(resultsB);

        comparison.put("retrievalMetricsA", avgA);
        comparison.put("retrievalMetricsB", avgB);

        // Delta
        Map<String, Double> delta = new HashMap<>();
        for (String key : avgA.keySet()) {
            double valA = avgA.getOrDefault(key, 0.0);
            double valB = avgB.getOrDefault(key, 0.0);
            delta.put(key, valB - valA);
        }
        comparison.put("retrievalDelta", delta);

        // Average generation metrics
        Map<String, Double> genA = computeAverageGenerationMetrics(resultsA);
        Map<String, Double> genB = computeAverageGenerationMetrics(resultsB);
        comparison.put("generationMetricsA", genA);
        comparison.put("generationMetricsB", genB);

        Map<String, Double> genDelta = new HashMap<>();
        for (String key : genA.keySet()) {
            double valA = genA.getOrDefault(key, 0.0);
            double valB = genB.getOrDefault(key, 0.0);
            genDelta.put(key, valB - valA);
        }
        comparison.put("generationDelta", genDelta);

        return comparison;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> computeAverageRetrievalMetrics(List<EvaluationResult> results) {
        Map<String, Double> totals = new HashMap<>();
        int count = 0;

        for (EvaluationResult result : results) {
            if (result.getRetrievalMetrics() == null) continue;
            try {
                Map<String, Object> metrics = objectMapper.readValue(
                        result.getRetrievalMetrics(),
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        totals.merge(entry.getKey(), ((Number) entry.getValue()).doubleValue(), Double::sum);
                    }
                }
                count++;
            } catch (Exception e) {
                log.debug("Failed to parse retrieval metrics: {}", e.getMessage());
            }
        }

        Map<String, Double> averages = new HashMap<>();
        if (count > 0) {
            for (Map.Entry<String, Double> entry : totals.entrySet()) {
                averages.put(entry.getKey(), entry.getValue() / count);
            }
        }
        return averages;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> computeAverageGenerationMetrics(List<EvaluationResult> results) {
        Map<String, Double> totals = new HashMap<>();
        int count = 0;

        for (EvaluationResult result : results) {
            if (result.getGenerationMetrics() == null) continue;
            try {
                Map<String, Object> metrics = objectMapper.readValue(
                        result.getGenerationMetrics(),
                        new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        totals.merge(entry.getKey(), ((Number) entry.getValue()).doubleValue(), Double::sum);
                    }
                }
                count++;
            } catch (Exception e) {
                log.debug("Failed to parse generation metrics: {}", e.getMessage());
            }
        }

        Map<String, Double> averages = new HashMap<>();
        if (count > 0) {
            for (Map.Entry<String, Double> entry : totals.entrySet()) {
                averages.put(entry.getKey(), entry.getValue() / count);
            }
        }
        return averages;
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
