package com.agi.assistant.service.rag;

import com.agi.assistant.config.EmbeddingConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入向量服务
 * <p>
 * 通过小米 OpenAI 兼容 API 调用嵌入模型，将文本转换为向量。
 * 使用 WebClient 进行 HTTP 调用，支持单条和批量嵌入。
 */
@Slf4j
@Lazy
@Service
public class EmbeddingService {

    /** 批量请求最大文本数（防止请求体过大） */
    private static final int MAX_BATCH_SIZE = 64;

    /** 单条文本最大字符数 */
    private static final int MAX_TEXT_LENGTH = 8192;

    private final EmbeddingConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(EmbeddingConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 将单条文本转换为嵌入向量。
     *
     * @param text 待嵌入文本
     * @return 浮点向量（维度由配置决定）
     */
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String truncated = truncateText(text);
        List<List<Float>> results = embedBatchInternal(List.of(truncated));
        return results.isEmpty() ? Collections.emptyList() : results.get(0);
    }

    /**
     * 批量将文本转换为嵌入向量。
     * <p>
     * 当文本数量超过 MAX_BATCH_SIZE 时自动分批请求并合并结果。
     *
     * @param texts 待嵌入文本列表
     * @return 嵌入向量列表，与输入一一对应
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 截断过长文本
        List<String> truncated = new ArrayList<>(texts.size());
        for (String text : texts) {
            truncated.add(text == null ? "" : truncateText(text));
        }

        // 分批请求
        List<List<Float>> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < truncated.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, truncated.size());
            List<String> batch = truncated.subList(i, end);
            List<List<Float>> batchResults = embedBatchInternal(batch);
            allEmbeddings.addAll(batchResults);
        }

        return allEmbeddings;
    }

    /**
     * 获取配置的向量维度。
     */
    public int getDimensions() {
        return config.getDimensions();
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 发送批量嵌入请求到 API。
     */
    private List<List<Float>> embedBatchInternal(List<String> texts) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("input", texts);
            requestBody.put("dimensions", config.getDimensions());

            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Sending embedding request: model={}, inputCount={}, dimensions={}",
                    config.getModel(), texts.size(), config.getDimensions());

            String responseJson = webClient.post()
                    .uri("/embeddings")
                    .bodyValue(requestJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseEmbeddingResponse(responseJson);

        } catch (Exception e) {
            log.warn("Embedding API unavailable, returning empty vectors: {}", e.getMessage());
            // Return empty vectors for each input text so callers can degrade gracefully
            List<List<Float>> fallback = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                fallback.add(Collections.emptyList());
            }
            return fallback;
        }
    }

    /**
     * 解析 OpenAI 兼容格式的嵌入响应。
     */
    private List<List<Float>> parseEmbeddingResponse(String responseJson) {
        try {
            EmbeddingResponse response = objectMapper.readValue(responseJson, EmbeddingResponse.class);
            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                log.warn("Empty embedding response");
                return Collections.emptyList();
            }

            // 按 index 排序以保证顺序与输入一致
            response.getData().sort((a, b) -> Integer.compare(
                    a.getIndex() != null ? a.getIndex() : 0,
                    b.getIndex() != null ? b.getIndex() : 0
            ));

            List<List<Float>> embeddings = new ArrayList<>();
            for (EmbeddingData data : response.getData()) {
                if (data.getEmbedding() != null) {
                    embeddings.add(data.getEmbedding());
                }
            }

            log.debug("Received {} embeddings, dimension={}",
                    embeddings.size(),
                    embeddings.isEmpty() ? 0 : embeddings.get(0).size());

            return embeddings;

        } catch (Exception e) {
            log.error("Failed to parse embedding response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse embedding response", e);
        }
    }

    /**
     * 截断文本到最大长度。
     */
    private String truncateText(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        log.warn("Text truncated from {} to {} characters", text.length(), MAX_TEXT_LENGTH);
        return text.substring(0, MAX_TEXT_LENGTH);
    }

    // ──────────────────────────────────────────────────────────────
    //  响应 DTO
    // ──────────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private String object;
        private List<EmbeddingData> data;
        private Usage usage;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingData {
        private String object;
        private List<Float> embedding;
        private Integer index;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Usage {
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
