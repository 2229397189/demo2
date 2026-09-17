package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.dto.EvaluationTaskRequest;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.EvaluationService;
import com.agi.assistant.service.evaluation.EvaluationMetricsAggregator;
import com.agi.assistant.service.evaluation.EvaluationRunner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // Average retrieval metrics（只聚合「已评估」样本；负值哨兵不计入）
        Map<String, Object> avgA = computeAverageRetrievalMetrics(resultsA);
        Map<String, Object> avgB = computeAverageRetrievalMetrics(resultsB);

        comparison.put("retrievalMetricsA", avgA);
        comparison.put("retrievalMetricsB", avgB);
        comparison.put("retrievalDelta", computeMetricDelta(avgA, avgB));

        // Average generation metrics（同样剔除未评估哨兵，并输出每条指标的样本数）
        Map<String, Object> genA = computeAverageGenerationMetrics(resultsA);
        Map<String, Object> genB = computeAverageGenerationMetrics(resultsB);
        comparison.put("generationMetricsA", genA);
        comparison.put("generationMetricsB", genB);
        comparison.put("generationDelta", computeMetricDelta(genA, genB));

        return comparison;
    }

    /**
     * 有效样本数键后缀：与指标值并列输出，例如 {@code faithfulnessEvaluatedCount}。
     * <p>
     * 上层据此判断某个均值背后有几条真实样本；为 0 表示该指标全部未评估。
     */
    static final String EVALUATED_COUNT_SUFFIX = EvaluationMetricsAggregator.EVALUATED_COUNT_SUFFIX;

    /**
     * 聚合检索指标（包级可见，便于单测）。委托 {@link EvaluationMetricsAggregator}，
     * 保证「对比页」与「评测快照」使用<b>同一套</b>聚合口径（单一实现，绝无两套算法）。
     */
    Map<String, Object> computeAverageRetrievalMetrics(List<EvaluationResult> results) {
        return EvaluationMetricsAggregator.retrievalAverages(results, objectMapper);
    }

    /**
     * 聚合生成指标（包级可见，便于单测）。委托 {@link EvaluationMetricsAggregator}，
     * 保证「对比页」与「评测快照」使用<b>同一套</b>聚合口径（单一实现，绝无两套算法）。
     */
    Map<String, Object> computeAverageGenerationMetrics(List<EvaluationResult> results) {
        return EvaluationMetricsAggregator.generationAverages(results, objectMapper);
    }

    /**
     * 计算两组指标 Map 的差值。
     * <p>
     * 仅对「两侧都存在真实数值」的指标计算差值；显式跳过未评估（{@code null}）与
     * 样本数键（{@code *EvaluatedCount}），避免把「没数据」当成 0 参与比较。
     */
    private Map<String, Double> computeMetricDelta(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Double> delta = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String key : keys) {
            if (key.endsWith(EVALUATED_COUNT_SUFFIX)) {
                continue;
            }
            Object va = a.get(key);
            Object vb = b.get(key);
            if (va instanceof Number na && vb instanceof Number nb) {
                delta.put(key, nb.doubleValue() - na.doubleValue());
            }
        }
        return delta;
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
