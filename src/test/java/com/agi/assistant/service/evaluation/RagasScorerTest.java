package com.agi.assistant.service.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link RagasScorer} 的离线单测。
 * <p>
 * 全部使用手算好的固定输入断言纯函数结果，不发起任何网络 / LLM / embedding 调用。
 */
class RagasScorerTest {

    private static Claim supported(String text) {
        Claim claim = new Claim(text);
        claim.setSupported(true);
        return claim;
    }

    private static Claim unsupported(String text) {
        // Claim(text) 默认 supported=false
        return new Claim(text);
    }

    @Test
    @DisplayName("faithfulness：3 条 claim 中 2 条被支持 → 2/3")
    void faithfulnessPartial() {
        List<Claim> claims = List.of(supported("a"), supported("b"), unsupported("c"));
        assertThat(RagasScorer.faithfulness(claims)).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("faithfulness：空列表 / null → 1.0（空答案视为无幻觉）")
    void faithfulnessEmpty() {
        assertThat(RagasScorer.faithfulness(List.of())).isEqualTo(1.0);
        assertThat(RagasScorer.faithfulness(null)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("averagePrecision：rel 在第 1、3 位 → AP=(1 + 2/3)/2 = 5/6 ≈ 0.8333")
    void averagePrecisionMixed() {
        // rel = [true,false,true]
        // P@1 = 1/1, P@3 = 2/3；AP = (1/1 + 2/3) / 2 = 5/6 ≈ 0.83333...
        List<Boolean> relevance = List.of(true, false, true);
        assertThat(RagasScorer.averagePrecision(relevance)).isCloseTo(5.0 / 6.0, within(1e-9));
    }

    @Test
    @DisplayName("averagePrecision：全 false / 空列表 → 0.0")
    void averagePrecisionNone() {
        assertThat(RagasScorer.averagePrecision(List.of(false, false, false))).isEqualTo(0.0);
        assertThat(RagasScorer.averagePrecision(List.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("contextRecall：4 条 golden claim 中 3 条覆盖 → 0.75；空 → 1.0")
    void contextRecallPartial() {
        List<Claim> golden = List.of(
                supported("a"), supported("b"), supported("c"), unsupported("d"));
        assertThat(RagasScorer.contextRecall(golden)).isCloseTo(0.75, within(1e-9));
        assertThat(RagasScorer.contextRecall(List.of())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("cosine：正交 → 0.0；同向 → 1.0；零向量 / 维度不一致 → NaN")
    void cosineBasics() {
        assertThat(RagasScorer.cosine(new double[]{1, 0}, new double[]{0, 1})).isEqualTo(0.0);
        assertThat(RagasScorer.cosine(new double[]{1, 2}, new double[]{1, 2}))
                .isCloseTo(1.0, within(1e-12));
        assertThat(RagasScorer.cosine(new double[]{0, 0}, new double[]{1, 1})).isNaN();
        assertThat(RagasScorer.cosine(new double[]{1, 2, 3}, new double[]{1, 2})).isNaN();
    }

    @Test
    @DisplayName("answerRelevancyJaccard：完全相同 → 1.0（英文/中文）；完全不同 → 0.0")
    void jaccardBasics() {
        assertThat(RagasScorer.answerRelevancyJaccard(List.of("hello world"), "hello world"))
                .isEqualTo(1.0);
        assertThat(RagasScorer.answerRelevancyJaccard(List.of("机器学习模型"), "机器学习模型"))
                .isEqualTo(1.0);
        assertThat(RagasScorer.answerRelevancyJaccard(List.of("apple"), "banana")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("answerRelevancy（向量）：余弦均值；维度不一致 / 空 → -1.0")
    void answerRelevancyEmbedding() {
        double[] original = {1, 0};
        List<double[]> generated = List.of(new double[]{1, 0}, new double[]{0, 1});
        // cos([1,0],[1,0]) = 1, cos([0,1],[1,0]) = 0 → 均值 0.5
        assertThat(RagasScorer.answerRelevancy(generated, original)).isCloseTo(0.5, within(1e-9));
        assertThat(RagasScorer.answerRelevancy(List.of(new double[]{1, 0, 0}), original))
                .isEqualTo(-1.0);
        assertThat(RagasScorer.answerRelevancy(List.<double[]>of(), original)).isEqualTo(-1.0);
    }
}
