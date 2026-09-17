package com.agi.assistant.service.impl;

import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.vo.Result;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link EvaluationServiceImpl} 指标平均聚合的离线单测。
 * <p>
 * 覆盖本次修复的核心诚信约束：
 * <ol>
 *   <li>负值（{@code -1.0} 等「不可用」哨兵）不参与平均，绝不拉低均值；</li>
 *   <li>全部样本不可用 → 指标值为 {@code null}（显式未评估），而不是 0 / -1；</li>
 *   <li>每条指标输出 {@code <指标名>EvaluatedCount} 表示真实样本数。</li>
 * </ol>
 * 不依赖 Spring 上下文、不发起任何 IO。
 */
class EvaluationServiceImplAggregationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 只用到 objectMapper，其余协作者传 null 即可（这些聚合方法不触碰它们）
    private final EvaluationServiceImpl service =
            new EvaluationServiceImpl(null, null, null, objectMapper);

    private EvaluationResult generationResult(Map<String, Object> metrics) throws Exception {
        EvaluationResult result = new EvaluationResult();
        result.setGenerationMetrics(objectMapper.writeValueAsString(metrics));
        return result;
    }

    private EvaluationResult retrievalResult(Map<String, Object> metrics) throws Exception {
        EvaluationResult result = new EvaluationResult();
        result.setRetrievalMetrics(objectMapper.writeValueAsString(metrics));
        return result;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("混合 -1.0 与正常值：均值只算正常值，未被拉低")
    void mixedUnavailableAndValidSamples() throws Exception {
        EvaluationResult r1 = generationResult(map(
                "faithfulness", 0.8,
                "answerRelevancy", -1.0,
                "contextPrecision", 0.5,
                "contextRecall", -1.0));
        EvaluationResult r2 = generationResult(map(
                "faithfulness", 0.6,
                "answerRelevancy", 0.9,
                "contextPrecision", -1.0,
                "contextRecall", -1.0));

        Map<String, Object> avg = service.computeAverageGenerationMetrics(List.of(r1, r2));

        // faithfulness: (0.8 + 0.6) / 2 = 0.7（两条都有效）
        assertThat((Double) avg.get("faithfulness")).isCloseTo(0.7, within(1e-9));
        // answerRelevancy: 只有 0.9 一条有效 → 0.9（而不是 (0.9 + -1.0)/2 = -0.05）
        assertThat(avg.get("answerRelevancy")).isEqualTo(0.9);
        // contextPrecision: 只有 0.5 一条有效 → 0.5
        assertThat(avg.get("contextPrecision")).isEqualTo(0.5);
        // contextRecall: 全部不可用 → null（显式未评估），不是 -1.0 / 0
        assertThat(avg.get("contextRecall")).isNull();

        // 有效样本数
        assertThat(avg.get("faithfulnessEvaluatedCount")).isEqualTo(2);
        assertThat(avg.get("answerRelevancyEvaluatedCount")).isEqualTo(1);
        assertThat(avg.get("contextPrecisionEvaluatedCount")).isEqualTo(1);
        assertThat(avg.get("contextRecallEvaluatedCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("全部样本为 -1.0：四个指标均为 null，样本数均为 0")
    void allSamplesUnavailable() throws Exception {
        EvaluationResult r1 = generationResult(map(
                "faithfulness", -1.0,
                "answerRelevancy", -1.0,
                "contextPrecision", -1.0,
                "contextRecall", -1.0));
        EvaluationResult r2 = generationResult(map(
                "faithfulness", -1.0,
                "answerRelevancy", -1.0,
                "contextPrecision", -1.0,
                "contextRecall", -1.0));

        Map<String, Object> avg = service.computeAverageGenerationMetrics(List.of(r1, r2));

        assertThat(avg.get("faithfulness")).isNull();
        assertThat(avg.get("answerRelevancy")).isNull();
        assertThat(avg.get("contextPrecision")).isNull();
        assertThat(avg.get("contextRecall")).isNull();
        assertThat(avg.get("faithfulnessEvaluatedCount")).isEqualTo(0);
        assertThat(avg.get("answerRelevancyEvaluatedCount")).isEqualTo(0);
        assertThat(avg.get("contextPrecisionEvaluatedCount")).isEqualTo(0);
        assertThat(avg.get("contextRecallEvaluatedCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("0.0 是有效分数（不是哨兵），应计入平均")
    void zeroIsAValidScore() throws Exception {
        EvaluationResult r1 = retrievalResult(map("mrr", 0.0));
        EvaluationResult r2 = retrievalResult(map("mrr", 1.0));

        Map<String, Object> avg = service.computeAverageRetrievalMetrics(List.of(r1, r2));

        assertThat(avg.get("mrr")).isEqualTo(0.5);
        assertThat(avg.get("mrrEvaluatedCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("检索指标按同样规则聚合：跳过负值、输出样本数")
    void retrievalMetricsAggregated() throws Exception {
        EvaluationResult r1 = retrievalResult(map(
                "recallAtK", 0.5,
                "precisionAtK", 0.25,
                "mrr", 0.0,
                "k", 10));
        EvaluationResult r2 = retrievalResult(map(
                "recallAtK", -1.0,   // 假设的不可用哨兵 → 跳过
                "precisionAtK", 0.25,
                "mrr", 1.0,
                "k", 10));

        Map<String, Object> avg = service.computeAverageRetrievalMetrics(List.of(r1, r2));

        assertThat(avg.get("recallAtK")).isEqualTo(0.5);
        assertThat(avg.get("recallAtKEvaluatedCount")).isEqualTo(1);
        assertThat(avg.get("precisionAtK")).isEqualTo(0.25);
        assertThat(avg.get("precisionAtKEvaluatedCount")).isEqualTo(2);
        assertThat(avg.get("mrr")).isEqualTo(0.5);
        assertThat(avg.get("mrrEvaluatedCount")).isEqualTo(2);
        // k 是检索深度参数，不是指标 → 既不出现在均值，也不输出样本数
        assertThat(avg).doesNotContainKey("k");
        assertThat(avg).doesNotContainKey("kEvaluatedCount");
    }

    @Test
    @DisplayName("未评估指标在 JSON 里显式输出为 null，而不是键消失")
    void unavailableMetricSerializedAsExplicitNull() throws Exception {
        // 复刻 Spring Boot 的全局配置：spring.jackson.default-property-inclusion = non_null
        ObjectMapper springLike = new ObjectMapper();
        springLike.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        EvaluationServiceImpl svc = new EvaluationServiceImpl(null, null, null, springLike);

        // 一条样本：faithfulness 有效，contextRecall 未评估（-1.0）
        EvaluationResult r1 = generationResult(map("faithfulness", 0.8, "contextRecall", -1.0));
        Map<String, Object> metrics = svc.computeAverageGenerationMetrics(List.of(r1));

        // 复刻响应结构：Result.ok(compareResults 的 Map)
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("generationMetricsA", metrics);
        String json = springLike.writeValueAsString(Result.ok(comparison));

        // 关键断言：未评估指标必须是显式 null，前端才能区分「未评估」与「字段不存在」
        assertThat(json).contains("\"contextRecall\":null");
        // 有效指标与样本数照常输出
        assertThat(json).contains("\"faithfulness\":0.8");
        assertThat(json).contains("\"contextRecallEvaluatedCount\":0");
        assertThat(json).contains("\"faithfulnessEvaluatedCount\":1");
    }

    @Test
    @DisplayName("空结果集：不产生任何指标键（没有数据就没有输出）")
    void emptyResults() {
        assertThat(service.computeAverageGenerationMetrics(List.of())).isEmpty();
        assertThat(service.computeAverageRetrievalMetrics(List.of())).isEmpty();
    }
}
