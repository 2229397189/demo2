package com.agi.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 火山方舟（Ark）provider 配置。
 * <p>
 * 与项目既有 {@code OpenAIConfig} 保持一致的落地风格：{@code @Configuration} +
 * {@code @ConfigurationProperties}，因此是一个可注入的 Spring Bean。
 * <p>
 * 降级约定（对齐 C2）：本类只做属性绑定，**构造期不做任何网络连接**；
 * {@code ark.enabled=false} 或 {@code api-key} 为空时，Ark provider 仅呈现为不可用，
 * 由 Router 回退 GLM，不影响应用启动。
 *
 * @author Alex
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ark")
public class ArkProperties {

    /** 是否启用方舟 provider，默认关闭。 */
    private boolean enabled = false;

    /** 方舟 OpenAI 兼容端点基础地址。 */
    private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";

    /** API Key，空视为不可用。 */
    private String apiKey = "";

    /** 方舟接入点(endpoint) id，空则不发起 Ark 调用。 */
    private String model = "";

    /** 请求超时（秒）。 */
    private int timeout = 120;

    /** 连接超时（秒）。 */
    private int connectTimeout = 30;
}
