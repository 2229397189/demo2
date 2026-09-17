package com.agi.assistant.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArkProperties} 绑定行为验证 —— 同时锁住一个关键平台事实。
 * <p>
 * 背景：{@code application.yml} 通过 {@code spring.config.import: optional:file:.env[.properties]}
 * 把 {@code .env} 当作 properties 文件加载，键名形如 {@code ARK_ENABLED} / {@code ARK_API_KEY}。
 * 本测试用 {@link ApplicationContextRunner} 以纯 {@code MapPropertySource} 形式注入属性，验证：
 * <ul>
 *   <li>kebab-case 键（{@code ark.enabled}）能正常绑定；</li>
 *   <li>UPPER_SNAKE 键（{@code ARK_ENABLED}）<b>不会</b>被 {@code @ConfigurationProperties} 宽松绑定
 *       —— 宽松绑定只对真实 OS 环境变量（{@code SystemEnvironmentPropertySource}）生效，
 *       对 properties 文件里的 UPPER_SNAKE 键无效。</li>
 * </ul>
 * 正因如此，{@code application.yml} 中的 {@code ark.*} / {@code llm.provider} 必须写成
 * {@code ${ARK_ENABLED:false}} 这类<b>精确键名占位符</b>（与既有 {@code ${OPENAI_API_KEY:}} 一致），
 * 才能从 {@code .env} 取值 —— 这也是本测试存在的意义。
 * <p>
 * 完全离线，不依赖任何外部服务。
 *
 * @author Alex
 */
class ArkPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    /** 仅注册 {@link ArkProperties} 及其绑定后处理器，不启动任何自动配置。 */
    @Configuration
    @EnableConfigurationProperties(ArkProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("kebab-case 键可正常绑定到 @ConfigurationProperties(prefix=\"ark\")")
    void bindsKebabCaseKeys() {
        runner.withPropertyValues(
                        "ark.enabled=true",
                        "ark.base-url=https://example.invalid/api/v3",
                        "ark.api-key=dummy-key",
                        "ark.model=dummy-model")
                .run(ctx -> {
                    ArkProperties props = ctx.getBean(ArkProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getBaseUrl()).isEqualTo("https://example.invalid/api/v3");
                    assertThat(props.getApiKey()).isEqualTo("dummy-key");
                    assertThat(props.getModel()).isEqualTo("dummy-model");
                });
    }

    @Test
    @DisplayName("UPPER_SNAKE 键不会宽松绑定（故 yml 必须用 ${ARK_*:} 占位符取值）")
    void upperSnakeKeysDoNotRelaxBind() {
        runner.withPropertyValues(
                        "ARK_ENABLED=true",
                        "ARK_API_KEY=dummy-key",
                        "ARK_MODEL=dummy-model")
                .run(ctx -> {
                    ArkProperties props = ctx.getBean(ArkProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getApiKey()).isEmpty();
                    assertThat(props.getModel()).isEmpty();
                });
    }
}
