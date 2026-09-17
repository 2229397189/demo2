package com.agi.assistant.service.llm;

import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智谱 GLM 模型 provider（OpenAI 兼容协议）。
 * <p>
 * 直接复用项目既有的 {@link OpenAIConfig}：其 {@code openAiWebClient()} 已按
 * {@code openai.base-url} / {@code openai.api-key} / 超时构建好 WebClient，
 * 因此本类只负责组装请求体、发起 {@code POST /chat/completions} 并解析
 * {@code choices[0].message.content}。
 * <p>
 * 约定（对齐 {@link ModelProvider}）：{@link #isAvailable()} 只读配置、不发起网络请求；
 * 构造期不建连（WebClient 由 {@link OpenAIConfig} 以 Spring Bean 方式惰性创建）。
 *
 * @author Alex
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GlmModelProvider implements ModelProvider {

    /** provider 名称常量。 */
    public static final String NAME = "glm";

    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * GLM 可用性判定：仅当 api-key 与 model 均已配置时视为可用。
     * <p>
     * 该方法不发起任何网络请求，可被路由层与健康检查端点安全、廉价地调用。
     */
    @Override
    public boolean isAvailable() {
        return hasText(openAIConfig.getApiKey()) && hasText(openAIConfig.getModel());
    }

    @Override
    public String chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
        if (!isAvailable()) {
            throw new ProviderUnavailableException(NAME, "GLM provider 不可用：缺少 api-key 或 model");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", openAIConfig.getModel());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        // 复用 OpenAIConfig 的思维链开关（默认 disabled），避免 glm-4.5-air 把 token 烧在 reasoning 上
        openAIConfig.applyThinking(body);

        log.debug("GLM chat 请求：model={}, messages={}", openAIConfig.getModel(), messages == null ? 0 : messages.size());

        String response = openAIConfig.openAiWebClient()
                .post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(openAIConfig.getTimeout()));

        return parseContent(response);
    }

    /**
     * 从非流式 {@code /chat/completions} 响应中解析出回复文本。
     * <p>
     * 内容为空时把原始响应体（含可能的 {@code error} 字段）带进异常，便于排障，
     * 而不是静默返回空串。
     */
    private String parseContent(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("GLM 返回空响应体");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.at("/choices/0/message/content");
            String content = (contentNode.isMissingNode() || contentNode.isNull()) ? "" : contentNode.asText("");
            if (content.isBlank()) {
                JsonNode error = root.path("error");
                String detail = (!error.isMissingNode() && !error.isNull()) ? error.toString() : response;
                throw new IllegalStateException("GLM 返回内容为空，原始响应: " + detail);
            }
            return content.trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 GLM 响应失败，原始响应: " + response, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
