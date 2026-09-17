package com.agi.assistant.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ModelProviderRouter} 离线单测。
 * <p>
 * 全部使用<b>手写 stub</b> 的 {@link ModelProvider}，不发起任何网络请求、不花费任何额度。
 * 覆盖四条回退路径：
 * <ol>
 *   <li>配置的 provider 可用 → 直接走它；</li>
 *   <li>配置的 provider 不可用 → 回退 GLM（断言实际调用的是 GLM）；</li>
 *   <li>配置了不存在的 provider 名 → 回退 GLM；</li>
 *   <li>GLM 也不可用 → 抛 {@link ProviderUnavailableException}。</li>
 * </ol>
 *
 * @author Alex
 */
class ModelProviderRouterTest {

    /** 记录调用次数的手写 stub provider。 */
    private static final class StubProvider implements ModelProvider {

        private final String name;
        private final boolean available;
        private final String reply;
        final AtomicInteger calls = new AtomicInteger();

        StubProvider(String name, boolean available, String reply) {
            this.name = name;
            this.available = available;
            this.reply = reply;
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
            calls.incrementAndGet();
            return reply;
        }
    }

    private static final List<Map<String, String>> MESSAGES =
            List.of(Map.of("role", "user", "content", "hi"));

    @Test
    @DisplayName("配置的 provider 可用 → 直接走它，不触碰 GLM")
    void usesConfiguredProviderWhenAvailable() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, true, "glm-reply");
        StubProvider ark = new StubProvider(ArkModelProvider.NAME, true, "ark-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm, ark), ArkModelProvider.NAME);

        String result = router.chat(MESSAGES, 0.0, 16);

        assertThat(result).isEqualTo("ark-reply");
        assertThat(ark.calls.get()).as("应调用配置的 ark").isEqualTo(1);
        assertThat(glm.calls.get()).as("不应触碰 GLM").isZero();
        assertThat(router.activeProviderName()).isEqualTo(ArkModelProvider.NAME);
    }

    @Test
    @DisplayName("配置的 provider 不可用 → 回退 GLM（断言实际调用 GLM）")
    void fallsBackToGlmWhenConfiguredUnavailable() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, true, "glm-reply");
        StubProvider ark = new StubProvider(ArkModelProvider.NAME, false, "ark-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm, ark), ArkModelProvider.NAME);

        String result = router.chat(MESSAGES, 0.0, 16);

        assertThat(result).isEqualTo("glm-reply");
        assertThat(glm.calls.get()).as("应回退调用 GLM").isEqualTo(1);
        assertThat(ark.calls.get()).as("不可用的 ark 不应被调用").isZero();
        assertThat(router.activeProviderName()).isEqualTo(GlmModelProvider.NAME);
    }

    @Test
    @DisplayName("配置了不存在的 provider 名 → 回退 GLM")
    void fallsBackToGlmWhenConfiguredNameUnknown() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, true, "glm-reply");
        StubProvider ark = new StubProvider(ArkModelProvider.NAME, true, "ark-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm, ark), "gemini");

        String result = router.chat(MESSAGES, 0.0, 16);

        assertThat(result).isEqualTo("glm-reply");
        assertThat(glm.calls.get()).as("未知 provider 名应回退 GLM").isEqualTo(1);
        assertThat(ark.calls.get()).isZero();
        assertThat(router.activeProviderName()).isEqualTo(GlmModelProvider.NAME);
    }

    @Test
    @DisplayName("GLM 也不可用 → 抛 ProviderUnavailableException，不伪造回复")
    void throwsWhenNoProviderAvailable() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, false, "glm-reply");
        StubProvider ark = new StubProvider(ArkModelProvider.NAME, false, "ark-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm, ark), ArkModelProvider.NAME);

        assertThatThrownBy(() -> router.chat(MESSAGES, 0.0, 16))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining(GlmModelProvider.NAME);

        assertThat(glm.calls.get()).isZero();
        assertThat(ark.calls.get()).isZero();
        assertThat(router.activeProviderName()).isNull();
    }

    @Test
    @DisplayName("availability() 顺序稳定；configured/active 名称正确")
    void availabilityAndActiveProvider() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, true, "glm-reply");
        StubProvider ark = new StubProvider(ArkModelProvider.NAME, false, "ark-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm, ark), ArkModelProvider.NAME);

        Map<String, Boolean> availability = router.availability();

        assertThat(availability).containsExactly(
                Map.entry(GlmModelProvider.NAME, true),
                Map.entry(ArkModelProvider.NAME, false));
        assertThat(router.configuredProviderName()).isEqualTo(ArkModelProvider.NAME);
        assertThat(router.activeProviderName()).isEqualTo(GlmModelProvider.NAME);
        assertThat(router.providers()).hasSize(2);
    }

    @Test
    @DisplayName("配置为空白字符串时默认按 glm 处理")
    void blankConfiguredProviderDefaultsToGlm() {
        StubProvider glm = new StubProvider(GlmModelProvider.NAME, true, "glm-reply");
        ModelProviderRouter router = new ModelProviderRouter(List.of(glm), "   ");

        assertThat(router.configuredProviderName()).isEqualTo(GlmModelProvider.NAME);
        assertThat(router.chat(MESSAGES, 0.0, 16)).isEqualTo("glm-reply");
    }
}
