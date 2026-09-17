package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.dto.EvaluationSnapshot;
import com.agi.assistant.model.entity.EvaluationResult;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.llm.ModelProvider;
import com.agi.assistant.service.llm.ModelProviderRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EvaluationSnapshotService} 的<b>离线单测</b>。
 * <p>
 * 不启动 Spring 上下文、不连 DB：mappers 用 Mockito stub，模型路由用内存 fake。
 * 覆盖四条核心诚信 / 确定性约束：
 * <ol>
 *   <li><b>结构确定性</b>：同一份输入导出两次，除 {@code meta.generatedAt} 外逐字节相同；</li>
 *   <li><b>未评估语义</b>：全部指标不可用（{@code -1.0} 哨兵）→ 指标为 {@code null} 且
 *       {@code evaluated=false}，<b>断言它不是 0、也不是 -1.0</b>；</li>
 *   <li><b>混合样本</b>：正常值 + 哨兵混合 → 均值只算正常值，{@code EvaluatedCount} 等于真实样本数；</li>
 *   <li><b>排序稳定</b>：{@code perQuery} 按 {@code queryId} 升序（乱序输入 → 有序输出）。</li>
 * </ol>
 *
 * @author Alex
 */
class EvaluationSnapshotTest {

    private final ObjectMapper om = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ----------------------------------------------------------------
    //  测试 1：结构确定性
    // ----------------------------------------------------------------

    @Test
    @DisplayName("同一 task 导出两次：除 generatedAt 外 JSON 逐字节相同")
    void twoExportsAreByteIdenticalExceptGeneratedAt() throws Exception {
        EvaluationSnapshotService svc = serviceWith(sampleResults(), task());
        EvaluationSnapshot s1 = svc.exportSnapshot(7L, "HYBRID");
        EvaluationSnapshot s2 = svc.exportSnapshot(7L, "HYBRID");

        String j1 = svc.toStableJson(s1).replace(s1.getMeta().getGeneratedAt(), "<TS>");
        String j2 = svc.toStableJson(s2).replace(s2.getMeta().getGeneratedAt(), "<TS>");

        assertThat(j1).isEqualTo(j2);

        // 快照文件确实落盘（json + md）
        assertThat(svc.listSnapshots())
                .extracting(m -> m.get("fileName"))
                .anyMatch(n -> String.valueOf(n).endsWith(".json"))
                .anyMatch(n -> String.valueOf(n).endsWith(".md"));
    }

    // ----------------------------------------------------------------
    //  测试 2：未评估语义
    // ----------------------------------------------------------------

    @Test
    @DisplayName("全部指标不可用(-1.0)：输出 null 且 evaluated=false，不是 0、不是 -1.0")
    void allUnavailableMetricsAreNullNotZeroNorSentinel() throws Exception {
        List<EvaluationResult> results = List.of(
                result(1, retrieval(-1.0, -1.0, -1.0, -1.0, -1.0),
                        generation(-1.0, -1.0, -1.0, -1.0), 50, "q", "a"));

        EvaluationSnapshotService svc = serviceWith(results, task());
        EvaluationSnapshot s = svc.exportSnapshot(7L, "HYBRID");

        // 汇总：全部未评估
        assertThat(s.getRetrieval().isEvaluated()).isFalse();
        assertThat(s.getRetrieval().getRecallAtK()).isNull();
        assertThat(s.getRetrieval().getMrr()).isNull();
        assertThat(s.getRetrieval().getNdcgAtK()).isNull();
        assertThat(s.getGeneration().isEvaluated()).isFalse();
        assertThat(s.getGeneration().getFaithfulness()).isNull();
        assertThat(s.getGeneration().getContextRecall()).isNull();

        // 关键：不是 0、也不是 -1.0。
        // AssertJ 对 null 调用 isNotEqualTo(Double) 会直接报「actual 不能为 null」，
        // 故先用 Objects.equals 表达「不等于任何数字」，再单独断言其为 null。
        Double summaryMrr = s.getRetrieval().getMrr();
        assertThat(summaryMrr).isNull();
        assertThat(Objects.equals(summaryMrr, 0.0)).isFalse();
        assertThat(Objects.equals(summaryMrr, -1.0)).isFalse();

        // 逐 query 同样未评估
        assertThat(s.getPerQuery().get(0).getRetrieval().getMrr()).isNull();
        assertThat(s.getPerQuery().get(0).getGeneration().isEvaluated()).isFalse();

        // JSON 里字段「存在且为显式 null」（不是键消失、更不是 0）
        JsonNode root = om.readTree(svc.toStableJson(s));
        assertThat(root.path("retrieval").has("ndcgAtK")).isTrue();
        assertThat(root.path("retrieval").path("ndcgAtK").isNull()).isTrue();
        assertThat(root.path("generation").path("faithfulness").isNull()).isTrue();
    }

    // ----------------------------------------------------------------
    //  测试 3：混合样本
    // ----------------------------------------------------------------

    @Test
    @DisplayName("混合正常值与 -1.0：均值只算正常值，EvaluatedCount 等于真实样本数")
    void mixedSamplesAverageOnlyValidOnes() throws Exception {
        List<EvaluationResult> results = List.of(
                result(1, null, generation(0.8, -1.0, 0.5, -1.0), 10, "q1", "a1"),
                result(2, null, generation(0.6, 0.9, -1.0, -1.0), 20, "q2", "a2"));

        // 直接驱动与「对比页」共享的聚合器（同一实现）
        Map<String, Object> avg = EvaluationMetricsAggregator.generationAverages(results, om);
        assertThat((Double) avg.get("faithfulness")).isEqualTo(0.7);
        assertThat(avg.get("faithfulnessEvaluatedCount")).isEqualTo(2);
        assertThat(avg.get("contextRecall")).isNull();
        assertThat(avg.get("contextRecallEvaluatedCount")).isEqualTo(0);
        assertThat(avg.get("answerRelevancy")).isEqualTo(0.9); // 不是 (0.9 + -1.0)/2 = -0.05

        // 快照里同样体现该口径
        EvaluationSnapshotService svc = serviceWith(results, task());
        EvaluationSnapshot s = svc.exportSnapshot(7L, "HYBRID");
        assertThat(s.getGeneration().getFaithfulness()).isEqualTo(0.7);
        assertThat(s.getGeneration().getAnswerRelevancy()).isEqualTo(0.9);
        assertThat(s.getGeneration().getContextRecall()).isNull();
        assertThat(s.getGeneration().isEvaluated()).isTrue();
    }

    // ----------------------------------------------------------------
    //  测试 4：排序稳定
    // ----------------------------------------------------------------

    @Test
    @DisplayName("perQuery 按 queryId 升序（乱序输入 → 有序输出）")
    void perQuerySortedByQueryIdAscending() throws Exception {
        List<EvaluationResult> results = new ArrayList<>(List.of(
                result(3, retrieval(0.3, 0.3, 0.3, 0.3, 1.0), generation(0.3, 0.3, 0.3, 0.3), 30, "q3", "a3"),
                result(1, retrieval(0.1, 0.1, 0.1, 0.1, 1.0), generation(0.1, 0.1, 0.1, 0.1), 10, "q1", "a1"),
                result(2, retrieval(0.2, 0.2, 0.2, 0.2, 1.0), generation(0.2, 0.2, 0.2, 0.2), 20, "q2", "a2")));

        EvaluationSnapshotService svc = serviceWith(results, task());
        EvaluationSnapshot s = svc.exportSnapshot(7L, "HYBRID");

        assertThat(s.getPerQuery())
                .extracting(EvaluationSnapshot.QueryRecord::getQueryId)
                .containsExactly(1L, 2L, 3L);
    }

    // ----------------------------------------------------------------
    //  测试 5：空结果 → null 而非 0（不编造）
    // ----------------------------------------------------------------

    @Test
    @DisplayName("0 条结果：指标为 null、queryCount=0，绝不填 0")
    void emptyResultsProduceNullMetricsNotZeros() throws Exception {
        EvaluationSnapshotService svc = serviceWith(List.of(), task());
        EvaluationSnapshot s = svc.exportSnapshot(7L, "HYBRID");

        assertThat(s.getMeta().getQueryCount()).isZero();
        assertThat(s.getPerQuery()).isEmpty();
        assertThat(s.getRetrieval().isEvaluated()).isFalse();
        assertThat(s.getRetrieval().getMrr()).isNull();
        assertThat(s.getRetrieval().getRecallAtK()).isNull();
        assertThat(s.getGeneration().isEvaluated()).isFalse();
        assertThat(s.getGeneration().getFaithfulness()).isNull();
    }

    // ----------------------------------------------------------------
    //  辅助
    // ----------------------------------------------------------------

    private EvaluationSnapshotService serviceWith(List<EvaluationResult> results, EvaluationTask task) {
        EvaluationTaskMapper taskMapper = mock(EvaluationTaskMapper.class);
        EvaluationResultMapper resultMapper = mock(EvaluationResultMapper.class);
        when(taskMapper.selectById(any())).thenReturn(task);
        when(resultMapper.selectList(any())).thenReturn(results);
        ModelProviderRouter router =
                new ModelProviderRouter(List.of(new FakeProvider("glm", true)), "glm");
        return new EvaluationSnapshotService(taskMapper, resultMapper, router, om, tempDir.toString());
    }

    private List<EvaluationResult> sampleResults() throws Exception {
        return List.of(
                result(2, retrieval(0.5, 0.25, 0.5, 0.3, 1.0), generation(0.8, 0.9, 0.5, 0.4), 120, "q2", "a2"),
                result(1, retrieval(0.6, 0.30, 1.0, 0.5, 1.0), generation(0.7, 0.8, 0.6, -1.0), 100, "q1", "a1"));
    }

    private EvaluationTask task() {
        EvaluationTask t = new EvaluationTask();
        t.setId(7L);
        t.setDatasetId("sample-dataset");
        t.setRetrievalStrategy("HYBRID");
        t.setStatus(EvaluationStatus.COMPLETED.getCode());
        return t;
    }

    private EvaluationResult result(long queryId, Map<String, Object> retrieval,
                                    Map<String, Object> generation, long latency,
                                    String query, String answer) throws Exception {
        EvaluationResult r = new EvaluationResult();
        r.setTaskId(7L);
        r.setQueryId(queryId);
        r.setQuery(query);
        r.setGeneratedAnswer(answer);
        r.setLatencyMs(latency);
        r.setRetrievalMetrics(retrieval == null ? null : om.writeValueAsString(retrieval));
        r.setGenerationMetrics(generation == null ? null : om.writeValueAsString(generation));
        return r;
    }

    private Map<String, Object> retrieval(double recall, double precision, double mrr,
                                          double ndcg, double hit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recallAtK", recall);
        m.put("precisionAtK", precision);
        m.put("mrr", mrr);
        m.put("ndcgAtK", ndcg);
        m.put("hitRate", hit);
        m.put("k", 10); // 非指标数值：不应参与平均、也不应出现在快照里
        return m;
    }

    private Map<String, Object> generation(double faithfulness, double relevancy,
                                           double precision, double recall) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("faithfulness", faithfulness);
        m.put("answerRelevancy", relevancy);
        m.put("contextPrecision", precision);
        m.put("contextRecall", recall);
        return m;
    }

    /**
     * 内存版 {@link ModelProvider}，只用于让路由解析出确定的生效 provider 名。
     */
    private static final class FakeProvider implements ModelProvider {

        private final String name;
        private final boolean available;

        private FakeProvider(String name, boolean available) {
            this.name = name;
            this.available = available;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
            return "stub";
        }
    }
}
