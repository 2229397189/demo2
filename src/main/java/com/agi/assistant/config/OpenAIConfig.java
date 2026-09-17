package com.agi.assistant.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Data
@Configuration
@ConfigurationProperties(prefix = "openai")
public class OpenAIConfig {

    private String baseUrl;
    private String apiKey;
    private String model;
    private int maxTokens;
    private double temperature;
    private int timeout;
    private int connectTimeout;

    /**
     * 思维链开关，对应 openai.thinking（默认 disabled）。
     * <p>
     * 智谱 GLM 的 glm-4.5-air / glm-4.6v 默认开启思维链，简单任务下 reasoning_content
     * 会把 max_tokens 吃光导致 content 为空（实测同题 60 → 2 completion tokens）。
     * 取值：
     * <ul>
     *   <li>disabled / enabled —— 在请求体里附加 {"thinking":{"type":...}}</li>
     *   <li>留空 或 auto —— 不附加该字段，兼容不支持此参数的 OpenAI 兼容端点</li>
     * </ul>
     */
    private String thinking = "disabled";

    /**
     * 把思维链开关写入请求体（就地修改传入的 Map，因此调用方需传可变 Map）。
     * <p>
     * thinking 为空或 auto 时不附加字段，避免对不支持该参数的端点造成 400。
     */
    public void applyThinking(java.util.Map<String, Object> body) {
        if (body == null) {
            return;
        }
        if (thinking == null || thinking.isBlank() || "auto".equalsIgnoreCase(thinking)) {
            return;
        }
        body.put("thinking", java.util.Map.of("type", thinking));
    }

    @Bean
    public WebClient openAiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout * 1000)
                .responseTimeout(Duration.ofSeconds(timeout))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(timeout, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(timeout, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Bean("streamingWebClient")
    public WebClient streamingWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout * 1000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .responseTimeout(Duration.ofSeconds(timeout * 2));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }
}
