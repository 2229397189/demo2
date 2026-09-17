package com.agi.assistant.service.evaluation;

import com.agi.assistant.config.EmbeddingConfig;
import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.service.evaluation.llm.LlmJudge;
import com.agi.assistant.service.rag.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link GenerationEvaluator} 的离线单测（手写 fake 注入，无 Mockito、无 Spring 上下文、无网络）。
 * <p>
 * 验证四件事：
 * <ol>
 *   <li>四个指标在给定 stub 下精确等于手算值；</li>
 *   <li>某个子判断抛异常时，只有该指标变 {@code -1.0}，另外三个仍正常（互不影响）；</li>
 *   <li>空答案 → faithfulness = 1.0，且完全不调用 LLM；</li>
 *   <li>{@code ragas.enabled=false} → 走 legacy 单 prompt 路径。</li>
 * </ol>
 */
class GenerationEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ──────────────────────────────────────────────────────────────
    //  手写 fake
    // ──────────────────────────────────────────────────────────────

    /** 可编程的 LlmJudge fake：按输入文本/claim 返回预设结果，并统计调用次数。 */
    static final class FakeLlmJudge implements LlmJudge {

        /** 文本 → 分解出的 claim 列表。 */
        final Map<String, List<String>> claimsByText = new HashMap<>();
        /** claim 文本 → 是否被支持。 */
        final Map<String, Boolean> supportByClaim = new HashMap<>();
        /** 按检索排名给出的相关性判定（用完默认 false）。 */
        List<Boolean> relevance = new ArrayList<>();
        /** 反向生成的问题。 */
        List<String> reversedQuestions = new ArrayList<>();

        /** 非 null 时对应方法抛异常：decompose / isSupported / isRelevant / reverse。 */
        String failMethod;
        /** 最近一次调用是否成功（供上层判定不可用）。 */
        boolean lastCallSucceeded = true;

        int decomposeCalls;
        int supportCalls;
        int relevantCalls;
        int reverseCalls;

        private int relevanceIndex;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return "";
        }

        @Override
        public List<String> decomposeClaims(String text) {
            decomposeCalls++;
            if ("decompose".equals(failMethod)) {
                throw new RuntimeException("boom-decompose");
            }
            return claimsByText.getOrDefault(text, List.of());
        }

        @Override
        public boolean isSupported(String claim, String context) {
            supportCalls++;
            if ("isSupported".equals(failMethod)) {
                throw new RuntimeException("boom-support");
            }
            return Boolean.TRUE.equals(supportByClaim.get(claim));
        }

        @Override
        public boolean isRelevant(String question, String chunk) {
            relevantCalls++;
            if ("isRelevant".equals(failMethod)) {
                throw new RuntimeException("boom-relevant");
            }
            boolean result = relevanceIndex < relevance.size()
                    && Boolean.TRUE.equals(relevance.get(relevanceIndex));
            relevanceIndex++;
            return result;
        }

        @Override
        public List<String> reverseGenerateQuestions(String answer, int n) {
            reverseCalls++;
            if ("reverse".equals(failMethod)) {
                throw new RuntimeException("boom-reverse");
            }
            return reversedQuestions;
        }

        @Override
        public boolean isLastCallSucceeded() {
            return lastCallSucceeded;
        }
    }

    /** 可编程的 EmbeddingService fake：可控返回固定向量 / 标记本地降级。 */
    static final class FakeEmbeddingService extends EmbeddingService {

        List<List<Float>> batch = new ArrayList<>();
        boolean remote = true;
        long fallbackCount;

        FakeEmbeddingService() {
            super(new EmbeddingConfig());
        }

        @Override
        public List<List<Float>> embedBatch(List<String> texts) {
            return batch;
        }

        @Override
        public boolean isRemoteConfigured() {
            return remote;
        }

        @Override
        public Map<String, Object> getStatus() {
            Map<String, Object> status = new HashMap<>();
            status.put("localFallback", fallbackCount);
            return status;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  测试
    // ──────────────────────────────────────────────────────────────

    private FakeLlmJudge baseJudge() {
        FakeLlmJudge judge = new FakeLlmJudge();
        // 答案 "ANS" → claim c1,c2,c3；支持 c1,c2 → faithfulness = 2/3
        judge.claimsByText.put("ANS", List.of("c1", "c2", "c3"));
        judge.supportByClaim.put("c1", true);
        judge.supportByClaim.put("c2", true);
        judge.supportByClaim.put("c3", false);
        // 期望答案 "EXP" → golden claim e1..e4；覆盖 e1,e2,e3 → recall = 3/4
        judge.claimsByText.put("EXP", List.of("e1", "e2", "e3", "e4"));
        judge.supportByClaim.put("e1", true);
        judge.supportByClaim.put("e2", true);
        judge.supportByClaim.put("e3", true);
        judge.supportByClaim.put("e4", false);
        // 相关性 [true,false,true] → AP = (1/1 + 2/3)/2 = 5/6
        judge.relevance = List.of(true, false, true);
        // 反向生成的两个问题
        judge.reversedQuestions = List.of("q1", "q2");
        return judge;
    }

    private FakeEmbeddingService remoteEmbedding() {
        FakeEmbeddingService embedding = new FakeEmbeddingService();
        embedding.remote = true;
        // [原问题, q1, q2]：cos(Q,q1)=1, cos(Q,q2)=0 → 均值 0.5
        embedding.batch = List.of(
                List.of(1.0f, 0.0f),
                List.of(1.0f, 0.0f),
                List.of(0.0f, 1.0f));
        return embedding;
    }

    private GenerationEvaluator evaluator(FakeLlmJudge judge, FakeEmbeddingService embedding, boolean ragasEnabled) {
        // 新机制下 WebClient / OpenAIConfig 不参与；传 null WebClient 保证若非新机制路径被走到会立刻失败（离线安全）
        return new GenerationEvaluator(null, new OpenAIConfig(), objectMapper, judge, embedding, ragasEnabled);
    }

    @Test
    @DisplayName("四指标精确等于手算值（stub 注入）")
    void allMetricsExact() {
        GenerationEvaluator evaluator = evaluator(baseJudge(), remoteEmbedding(), true);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "ANS", List.of("ctx1", "ctx2", "ctx3"), "EXP");

        assertThat(m.getFaithfulness()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(m.getAnswerRelevancy()).isCloseTo(0.5, within(1e-9));
        assertThat(m.getContextPrecision()).isCloseTo(5.0 / 6.0, within(1e-9));
        assertThat(m.getContextRecall()).isCloseTo(0.75, within(1e-9));

        // 明细字段
        assertThat(m.getAnswerRelevancyMethod()).isEqualTo("embedding");
        assertThat(m.getFaithfulnessClaims()).hasSize(3);
        assertThat(m.getFaithfulnessClaims().get(0).isSupported()).isTrue();
        assertThat(m.getFaithfulnessClaims().get(2).isSupported()).isFalse();
        assertThat(m.getContextRelevance()).containsExactly(true, false, true);
        assertThat(m.getRecallClaims()).hasSize(4);
    }

    @Test
    @DisplayName("某个子判断抛异常 → 只有该指标为 -1.0，另外三个仍正常")
    void oneMetricFailsOthersUnaffected() {
        FakeLlmJudge judge = baseJudge();
        judge.failMethod = "isRelevant"; // 只影响 Context Precision
        GenerationEvaluator evaluator = evaluator(judge, remoteEmbedding(), true);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "ANS", List.of("ctx1", "ctx2", "ctx3"), "EXP");

        assertThat(m.getContextPrecision()).isEqualTo(-1.0);
        // 其余三个不受影响
        assertThat(m.getFaithfulness()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(m.getAnswerRelevancy()).isCloseTo(0.5, within(1e-9));
        assertThat(m.getContextRecall()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("LLM 标记不可用（isLastCallSucceeded=false）→ 四指标均 -1.0")
    void llmReportedUnavailable() {
        FakeLlmJudge judge = baseJudge();
        judge.lastCallSucceeded = false; // 调用没抛异常，但明确报告不可用
        GenerationEvaluator evaluator = evaluator(judge, remoteEmbedding(), true);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "ANS", List.of("ctx1", "ctx2", "ctx3"), "EXP");

        assertThat(m.getFaithfulness()).isEqualTo(-1.0);
        assertThat(m.getAnswerRelevancy()).isEqualTo(-1.0);
        assertThat(m.getContextPrecision()).isEqualTo(-1.0);
        assertThat(m.getContextRecall()).isEqualTo(-1.0);
        assertThat(m.getAnswerRelevancyMethod()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("空答案 → faithfulness = 1.0，且完全不调用 LLM")
    void emptyAnswerFaithfulnessOneNoLlmCall() {
        FakeLlmJudge judge = new FakeLlmJudge(); // 未配置任何返回
        GenerationEvaluator evaluator = evaluator(judge, remoteEmbedding(), true);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "", List.of(), null);

        assertThat(m.getFaithfulness()).isEqualTo(1.0);
        // 空答案 / 空上下文 / 无期望答案 → 不应触发任何 LLM 子判断
        assertThat(judge.decomposeCalls).isZero();
        assertThat(judge.supportCalls).isZero();
        assertThat(judge.relevantCalls).isZero();
        assertThat(judge.reverseCalls).isZero();

        // 空答案的可相关性、空上下文的可精确度也如实标注
        assertThat(m.getAnswerRelevancy()).isEqualTo(-1.0);
        assertThat(m.getAnswerRelevancyMethod()).isEqualTo("unavailable");
        assertThat(m.getContextPrecision()).isEqualTo(0.0);
        assertThat(m.getContextRecall()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("embedding 不可用 → 回退 Jaccard 并标注 method=jaccard")
    void embeddingUnavailableFallsBackToJaccard() {
        FakeLlmJudge judge = baseJudge();
        judge.reversedQuestions = List.of("Q"); // 与原问题一致 → Jaccard = 1.0
        FakeEmbeddingService embedding = new FakeEmbeddingService();
        embedding.remote = false; // 标记不可用
        GenerationEvaluator evaluator = evaluator(judge, embedding, true);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "ANS", List.of(), null);

        assertThat(m.getAnswerRelevancy()).isEqualTo(1.0);
        assertThat(m.getAnswerRelevancyMethod()).isEqualTo("jaccard");
    }

    @Test
    @DisplayName("ragas.enabled=false → 走 legacy 单 prompt 路径，不触发新机制的 LLM 子判断")
    void legacyPathWhenRagasDisabled() {
        FakeLlmJudge judge = baseJudge();
        GenerationEvaluator evaluator = evaluator(judge, remoteEmbedding(), false);

        GenerationEvaluator.GenerationMetrics m =
                evaluator.evaluate("Q", "ANS", List.of("ctx1"), "EXP");

        assertThat(m.getAnswerRelevancyMethod()).isEqualTo("legacy-single-prompt");
        // 新机制的子判断一次都没被调用
        assertThat(judge.decomposeCalls).isZero();
        assertThat(judge.supportCalls).isZero();
        assertThat(judge.relevantCalls).isZero();
        assertThat(judge.reverseCalls).isZero();
    }
}
