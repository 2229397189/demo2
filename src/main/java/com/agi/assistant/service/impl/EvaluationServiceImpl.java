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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
    static final String EVALUATED_COUNT_SUFFIX = "EvaluatedCount";

    /**
     * 非指标数值字段的忽略名单：这类字段虽然也是 Number，但不是「质量指标」，
     * 不能参与平均、也不输出对应的样本数。
     * <p>
     * 已核对来源（{@code RetrievalEvaluator.RetrievalMetrics} 的 JSON 键）：
     * {@code recallAtK / precisionAtK / mrr / ndcgAtK / hitRate} 是真正的指标；
     * {@code k}（检索深度参数）是唯一的非指标数值字段，故排除之。
     * 生成指标（{@code GenerationEvaluator.GenerationMetrics}）的明细字段均带
     * {@code @JsonIgnore} 不落 JSON，无额外非指标数值。
     */
    static final Set<String> NON_METRIC_NUMERIC_KEYS = Set.of("k");

    /**
     * 聚合检索指标（包级可见，便于单测）。
     *
     * @see #averageNumericMetrics(List, Function)
     */
    Map<String, Object> computeAverageRetrievalMetrics(List<EvaluationResult> results) {
        return averageNumericMetrics(results, EvaluationResult::getRetrievalMetrics);
    }

    /**
     * 聚合生成指标（包级可见，便于单测）。
     *
     * @see #averageNumericMetrics(List, Function)
     */
    Map<String, Object> computeAverageGenerationMetrics(List<EvaluationResult> results) {
        return averageNumericMetrics(results, EvaluationResult::getGenerationMetrics);
    }

    /**
     * 对一批评测结果里的数值指标做平均，带有两条<b>诚信约束</b>：
     * <ol>
     *   <li><b>负值即「不可用」哨兵</b>（如 {@code -1.0}），不是分数 —— 聚合时一律跳过，
     *       绝不拉低均值。改动前把 {@code -1.0} 当正常值混入平均，会让「有一条未评估」
     *       的指标均值被系统性拉低，等于产出一个错误数字。</li>
     *   <li><b>全部样本都不可用 → 该指标值为 {@code null}</b>（显式「未评估」），
     *       绝不填 {@code 0} / {@code -1} / 其他看起来像分数的占位值。</li>
     * </ol>
     * 每条指标额外输出 {@code <指标名>EvaluatedCount}，表示参与平均的真实样本数。
     *
     * @param results   评测结果列表
     * @param extractor 从结果中取出指标 JSON 字符串的函数（检索 / 生成）
     * @return 指标名 → 均值（未评估为 {@code null}），以及 {@code <指标名>EvaluatedCount} → 样本数
     */
    private Map<String, Object> averageNumericMetrics(List<EvaluationResult> results,
                                                      Function<EvaluationResult, String> extractor) {
        // 保持首次出现顺序，便于前端/日志稳定展示
        Set<String> keyOrder = new LinkedHashSet<>();
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (results != null) {
            for (EvaluationResult result : results) {
                if (result == null) {
                    continue;
                }
                String json = extractor.apply(result);
                if (json == null) {
                    continue;
                }
                try {
                    Map<String, Object> metrics = objectMapper.readValue(
                            json,
                            new TypeReference<Map<String, Object>>() {});
                    for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                        if (!(entry.getValue() instanceof Number number)) {
                            continue;
                        }
                        String key = entry.getKey();
                        // 非指标数值（如检索深度参数 k）不参与平均，也不输出样本数
                        if (NON_METRIC_NUMERIC_KEYS.contains(key)) {
                            continue;
                        }
                        keyOrder.add(key);
                        double value = number.doubleValue();
                        // 负值是「不可用」哨兵，不是分数 → 跳过，绝不参与平均
                        if (value < 0.0) {
                            continue;
                        }
                        totals.merge(key, value, Double::sum);
                        counts.merge(key, 1, Integer::sum);
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse metrics: {}", e.getMessage());
                }
            }
        }

        MetricAverages averages = new MetricAverages();
        for (String key : keyOrder) {
            int count = counts.getOrDefault(key, 0);
            // 全部样本都不可用 → null（显式「未评估」），而不是 0 或 -1
            averages.put(key, count > 0 ? totals.get(key) / count : null);
            averages.put(key + EVALUATED_COUNT_SUFFIX, count);
        }
        return averages;
    }

    /**
     * 指标均值载体。
     * <p>
     * 全局 Jackson 配置为 {@code default-property-inclusion: non_null}，它把
     * <b>内容包含（content inclusion）</b>也设成了 NON_NULL，导致 Map 里的 {@code null}
     * 值被直接丢弃 —— 前端拿到的 {@code undefined} 无法区分「该指标未评估」与
     * 「后端根本没这个字段」。
     * <p>
     * 用一个<b>只作用于本类</b>的序列化器强制把 {@code null} 写成 JSON {@code null}，
     * 全局序列化策略与其它接口都不受影响。
     */
    @JsonSerialize(using = MetricAveragesSerializer.class)
    static final class MetricAverages extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link MetricAverages} 的序列化器：与普通 Map 不同，它<b>显式输出 null 值</b>，
     * 这样「未评估」指标在响应 JSON 里是 {@code "contextRecall":null} 而非键消失。
     */
    static final class MetricAveragesSerializer extends JsonSerializer<MetricAverages> {
        @Override
        public void serialize(MetricAverages value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeStartObject();
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                gen.writeFieldName(entry.getKey());
                Object entryValue = entry.getValue();
                if (entryValue == null) {
                    gen.writeNull();
                } else {
                    serializers.defaultSerializeValue(entryValue, gen);
                }
            }
            gen.writeEndObject();
        }
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
