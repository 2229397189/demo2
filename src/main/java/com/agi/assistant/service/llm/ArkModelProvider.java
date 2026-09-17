package com.agi.assistant.service.llm;

import com.agi.assistant.config.ArkProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 火山方舟（Ark）模型 provider（OpenAI 兼容协议）。
 * <p>
 * 设计约束（对齐 C2「构造期不建连」）：
 * <ul>
 *   <li>构造期只读 {@link ArkProperties}，<b>不发起任何网络请求</b>；
 *       {@code ark.enabled=false} 或 api-key 为空时应用仍能正常启动。</li>
 *   <li>WebClient <b>懒建</b>：首次 {@link #chat} 调用时才真正 build（本实现选择「懒建」方案），
 *       避免 Ark 未启用时也去初始化 Netty 连接池。</li>
 * </ul>
 * 协议与 GLM 相同（OpenAI 兼容），仅 base-url 与 key 不同。调用失败时把<b>响应体里的错误信息</b>
 * 原样带进异常/日志（Ark 会返回 {@code {"error":{"code":"ModelNotOpen",...}}}，对排障极有价值）。
 *
 * @author Alex
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArkModelProvider implements ModelProvider {

    /** provider 名称常量。 */
    public static final String NAME = "ark";

    private final ArkProperties arkProperties;
    private final ObjectMapper objectMapper;

    /**
     * 懒构建的 WebClient。构造期保持为 {@code null}（即「不建连」）；
     * {@code volatile} 保证多线程可见性，配合双重检查锁保证只构建一次。
     */
    private volatile WebClient webClient;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Ark 可用性判定：{@code enabled=true} 且 api-key、model 均非空。
     * <p>
     * 只读配置，不发起网络请求。
     */
    @Override
    public boolean isAvailable() {
        return arkProperties.isEnabled()
                && hasText(arkProperties.getApiKey())
                && hasText(arkProperties.getModel());
    }

    @Override
    public String chat(List<Map<String, String>> messages, double temperature, int maxTokens) {
        if (!isAvailable()) {
            throw new ProviderUnavailableException(NAME,
                    "Ark provider 不可用：enabled=" + arkProperties.isEnabled()
                            + "，api-key 或 model 为空");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", arkProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        log.debug("Ark chat 请求：model={}, messages={}", arkProperties.getModel(), messages == null ? 0 : messages.size());

        String response = client()
                .post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(errBody -> new IllegalStateException(
                                "Ark 调用失败：status=" + resp.statusCode().value() + "，响应体=" + errBody)))
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(arkProperties.getTimeout()));

        return parseContent(response);
    }

    /**
     * 懒建 WebClient（双重检查锁）。首次调用 {@link #chat} 时才构建。
     */
    private WebClient client() {
        WebClient local = this.webClient;
        if (local == null) {
            synchronized (this) {
                local = this.webClient;
                if (local == null) {
                    local = buildClient();
                    this.webClient = local;
                }
            }
        }
        return local;
    }

    private WebClient buildClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, arkProperties.getConnectTimeout() * 1000)
                .responseTimeout(Duration.ofSeconds(arkProperties.getTimeout()))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(arkProperties.getTimeout(), TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(arkProperties.getTimeout(), TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(arkProperties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + arkProperties.getApiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * 从非流式 {@code /chat/completions} 响应中解析出回复文本。
     * <p>
     * 内容为空时把原始响应体（含 {@code error} 字段）带进异常。
     */
    private String parseContent(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Ark 返回空响应体");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.at("/choices/0/message/content");
            String content = (contentNode.isMissingNode() || contentNode.isNull()) ? "" : contentNode.asText("");
            if (content.isBlank()) {
                JsonNode error = root.path("error");
                String detail = (!error.isMissingNode() && !error.isNull()) ? error.toString() : response;
                throw new IllegalStateException("Ark 返回内容为空，原始响应: " + detail);
            }
            return content.trim();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Ark 响应失败，原始响应: " + response, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
