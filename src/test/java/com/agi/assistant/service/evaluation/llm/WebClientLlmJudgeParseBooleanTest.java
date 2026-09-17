package com.agi.assistant.service.evaluation.llm;

import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link WebClientLlmJudge} 布尔解析的离线单测（不发起任何网络 / LLM 调用）。
 * <p>
 * 锁定缺陷修复：此前 {@code parseBoolean} 用子串匹配（{@code contains("no")}），
 * 会把 {@code "unknown"}/{@code "none"} 误判成 {@code false}；且在无法解析时返回
 * {@code false} 却<b>不</b>把 {@code lastCallSucceeded} 置 false，导致上层把「解析失败」
 * 当成「LLM 明确回答 false」，无理由压低 Faithfulness。
 * <p>
 * 测试策略：先模拟 {@code chat()} 已成功（{@code lastCallSucceeded=true}），
 * 再直接调用私有 {@code parseBoolean} 断言「解析结果」与「可用性标记」两个维度。
 */
class WebClientLlmJudgeParseBooleanTest {

    private WebClientLlmJudge judge;

    @BeforeEach
    void setUp() {
        judge = new WebClientLlmJudge(
                mock(WebClient.class),
                mock(OpenAIConfig.class),
                new ObjectMapper(),
                512);
    }

    /**
     * 前置 {@code lastCallSucceeded=true}（模拟 chat() 成功拿到内容），再调用私有
     * {@code parseBoolean} —— 从而精确隔离「解析逻辑」对可用性标记的影响。
     */
    private boolean parse(String content) throws Exception {
        ReflectionTestUtils.setField(judge, "lastCallSucceeded", true);
        Method method = WebClientLlmJudge.class.getDeclaredMethod("parseBoolean", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(judge, content);
    }

    @Test
    @DisplayName("LLM 返回 \"unknown\"：不算「明确 false」—— 返回值 false 但 lastCallSucceeded=false")
    void unknownIsNotExplicitFalse() throws Exception {
        boolean result = parse("unknown");

        assertThat(result).isFalse();
        assertThat(judge.isLastCallSucceeded())
                .as("解析失败必须标记不可用，绝不能假装 LLM 明确回答了 false")
                .isFalse();
    }

    @Test
    @DisplayName("LLM 返回 \"none\"：含子串 no，同样不得被判成明确 false")
    void noneIsNotExplicitFalse() throws Exception {
        boolean result = parse("none");

        assertThat(result).isFalse();
        assertThat(judge.isLastCallSucceeded()).isFalse();
    }

    @Test
    @DisplayName("LLM 返回 \"true\"：解析为 true 且 lastCallSucceeded=true")
    void trueParsedAndSucceeded() throws Exception {
        assertThat(parse("true")).isTrue();
        assertThat(judge.isLastCallSucceeded()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 \"false\"：解析为 false 且 lastCallSucceeded=true（真·明确 false）")
    void falseParsedAndSucceeded() throws Exception {
        assertThat(parse("false")).isFalse();
        assertThat(judge.isLastCallSucceeded()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 \"yes\"：解析为 true 且 lastCallSucceeded=true")
    void yesParsedAndSucceeded() throws Exception {
        assertThat(parse("yes")).isTrue();
        assertThat(judge.isLastCallSucceeded()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 \"no\"：整词命中 → false 且 lastCallSucceeded=true")
    void noParsedAndSucceeded() throws Exception {
        assertThat(parse("no")).isFalse();
        assertThat(judge.isLastCallSucceeded()).isTrue();
    }

    @Test
    @DisplayName("大小写 / 首尾空白无关：\"  TRUE \" 解析为 true")
    void caseAndWhitespaceInsensitive() throws Exception {
        assertThat(parse("  TRUE ")).isTrue();
        assertThat(judge.isLastCallSucceeded()).isTrue();
    }

    @Test
    @DisplayName("空内容：无结论 → lastCallSucceeded=false，不猜测")
    void blankIsUnavailable() throws Exception {
        assertThat(parse("   ")).isFalse();
        assertThat(judge.isLastCallSucceeded()).isFalse();
    }
}
