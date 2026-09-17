package com.agi.assistant.service.evaluation;

import com.agi.assistant.model.entity.EvaluationResult;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 评测指标均值聚合的<b>唯一实现</b>（single source of truth）。
 * <p>
 * {@code EvaluationServiceImpl}（对比页）与 {@code EvaluationSnapshotService}（快照导出）
 * 都调用本类，从而保证「对比页看到的均值」与「快照里固化的均值」<b>口径完全一致</b>，
 * 不会出现两套算法互相打架。
 * <p>
 * 三条<b>诚信约束</b>（红线 C3）：
 * <ol>
 *   <li><b>负值即「不可用」哨兵</b>（如 {@code -1.0}），不是分数 —— 聚合时一律跳过，
 *       绝不拉低均值；</li>
 *   <li><b>全部样本都不可用 → 该指标值为 {@code null}</b>（显式「未评估」），
 *       绝不填 {@code 0} / {@code -1} / 其他看起来像分数的占位值；</li>
 *   <li>每条指标额外输出 {@code <指标名>EvaluatedCount}，表示参与平均的真实样本数。</li>
 * </ol>
 *
 * @author Alex
 */
@Slf4j
public final class EvaluationMetricsAggregator {

    /** 有效样本数键后缀：与指标值并列输出，例如 {@code faithfulnessEvaluatedCount}。 */
    public static final String EVALUATED_COUNT_SUFFIX = "EvaluatedCount";

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
    public static final Set<String> NON_METRIC_NUMERIC_KEYS = Set.of("k");

    private EvaluationMetricsAggregator() {
        // 工具类，禁止实例化
    }

    /**
     * 聚合检索指标（跳过未评估哨兵，输出样本数）。
     *
     * @param results      评测结果列表
     * @param objectMapper 用于解析 {@code retrieval_metrics} JSON
     * @return 指标名 → 均值（未评估为 {@code null}），以及 {@code <指标名>EvaluatedCount} → 样本数
     */
    public static Map<String, Object> retrievalAverages(List<EvaluationResult> results,
                                                        ObjectMapper objectMapper) {
        return averageNumericMetrics(results, EvaluationResult::getRetrievalMetrics, objectMapper);
    }

    /**
     * 聚合生成指标（跳过未评估哨兵，输出样本数）。
     *
     * @param results      评测结果列表
     * @param objectMapper 用于解析 {@code generation_metrics} JSON
     * @return 指标名 → 均值（未评估为 {@code null}），以及 {@code <指标名>EvaluatedCount} → 样本数
     */
    public static Map<String, Object> generationAverages(List<EvaluationResult> results,
                                                         ObjectMapper objectMapper) {
        return averageNumericMetrics(results, EvaluationResult::getGenerationMetrics, objectMapper);
    }

    /**
     * 对一批评测结果里的数值指标做平均，带有三条诚信约束（见类注释）。
     *
     * @param results      评测结果列表
     * @param extractor    从结果中取出指标 JSON 字符串的函数（检索 / 生成）
     * @param objectMapper 用于解析指标 JSON
     * @return 指标名 → 均值（未评估为 {@code null}），以及 {@code <指标名>EvaluatedCount} → 样本数；
     *         返回值使用 {@link MetricAverages}，其序列化会<b>显式输出 null</b>
     */
    public static Map<String, Object> averageNumericMetrics(List<EvaluationResult> results,
                                                            Function<EvaluationResult, String> extractor,
                                                            ObjectMapper objectMapper) {
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
    public static final class MetricAverages extends LinkedHashMap<String, Object> {
        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link MetricAverages} 的序列化器：与普通 Map 不同，它<b>显式输出 null 值</b>，
     * 这样「未评估」指标在响应 JSON 里是 {@code "contextRecall":null} 而非键消失。
     */
    public static final class MetricAveragesSerializer extends JsonSerializer<MetricAverages> {
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
}
