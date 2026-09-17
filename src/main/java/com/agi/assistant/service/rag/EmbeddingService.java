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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 嵌入向量服务。
 * <p>
 * 三级降级链，保证「embedding 供应商额度用尽 / 网络不可用」时稠密检索不会静默失效：
 * <ol>
 *   <li><b>remote</b>：调用 OpenAI 兼容的 {@code /embeddings} 接口（当前配置为阿里云百炼 text-embedding-v3）</li>
 *   <li><b>local</b>：本地特征哈希向量（signed feature hashing）。离线、零依赖、确定性，
 *       但<b>只反映词形重叠而非语义</b>，属于降级手段而非等价替代</li>
 *   <li><b>跳过</b>：provider=remote 且远程失败时返回空向量，由调用方跳过向量索引</li>
 * </ol>
 * 由 {@code embedding.provider} 控制：{@code remote} / {@code local} / {@code auto}（默认，远程失败自动降级）。
 *
 * @author AGI Assistant
 */
@Slf4j
@Lazy
@Service
public class EmbeddingService {

    /** 批量请求最大文本数（防止请求体过大） */
    private static final int MAX_BATCH_SIZE = 64;

    /** 单条文本最大字符数 */
    private static final int MAX_TEXT_LENGTH = 8192;

    /** 连接超时（秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    /** 读取超时（秒） */
    private static final int READ_TIMEOUT_SECONDS = 30;

    /** 本地哈希向量参与计算的最大 token 数，防止超长文本拖慢计算 */
    private static final int MAX_LOCAL_TOKENS = 4096;

    private final EmbeddingConfig config;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ── 运行时可观测性 ──────────────────────────────────────────
    private final AtomicLong remoteSuccessCount = new AtomicLong();
    private final AtomicLong remoteFailureCount = new AtomicLong();
    private final AtomicLong localFallbackCount = new AtomicLong();
    private volatile String lastRemoteError;
    private volatile long lastRemoteErrorAt;

    public EmbeddingService(EmbeddingConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();

        // 创建带超时配置的 HttpClient
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_SECONDS * 1000)
                .responseTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS));

        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com/v1")
                .defaultHeader("Authorization", "Bearer " + (config.getApiKey() != null ? config.getApiKey() : ""))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        log.info("EmbeddingService 初始化：provider={}, model={}, dimensions={}, baseUrl={}, apiKeyConfigured={}",
                resolveMode(), config.getModel(), config.getDimensions(),
                config.getBaseUrl(), isRemoteConfigured());
    }

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 将单条文本转换为嵌入向量。
     *
     * @param text 待嵌入文本
     * @return 浮点向量（维度由配置决定）；文本为空时返回空列表
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
     * 当文本数量超过 {@link #MAX_BATCH_SIZE} 时自动分批请求并合并结果。
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

    /**
     * 远程 Embedding 是否可用（配置了 Key 且非 local 模式）。
     */
    public boolean isRemoteConfigured() {
        return !"local".equalsIgnoreCase(resolveMode())
                && config.getApiKey() != null
                && !config.getApiKey().isBlank();
    }

    /**
     * 返回当前 embedding 运行状态，供诊断接口与前端展示。
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", resolveMode());
        status.put("remoteConfigured", isRemoteConfigured());
        status.put("model", config.getModel());
        status.put("dimensions", config.getDimensions());
        status.put("baseUrl", config.getBaseUrl());
        status.put("remoteSuccess", remoteSuccessCount.get());
        status.put("remoteFailure", remoteFailureCount.get());
        status.put("localFallback", localFallbackCount.get());
        status.put("lastRemoteError", lastRemoteError);
        status.put("lastRemoteErrorAt", lastRemoteErrorAt);
        status.put("note", "localFallback 表示已降级为本地哈希向量（仅词形重叠，非语义）");
        return status;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 按当前 provider 模式生成向量。
     */
    private List<List<Float>> embedBatchInternal(List<String> texts) {
        String mode = resolveMode();

        if ("local".equals(mode)) {
            return localEmbedBatch(texts);
        }

        if (!isRemoteConfigured()) {
            log.warn("Embedding API Key 未配置（provider={}）", mode);
            if ("auto".equals(mode)) {
                return localEmbedBatch(texts);
            }
            return emptyVectors(texts.size());
        }

        try {
            List<List<Float>> remote = remoteEmbedBatch(texts);
            // 远程返回条数与输入不一致视为异常响应
            if (remote.size() == texts.size() && remote.stream().allMatch(v -> v != null && !v.isEmpty())) {
                remoteSuccessCount.incrementAndGet();
                return remote;
            }
            log.warn("Embedding 远程返回数量不匹配：期望 {} 实际 {}",
                    texts.size(), remote.size());
            recordRemoteError("远程返回条数与输入不匹配");
            if ("auto".equals(mode)) {
                return localEmbedBatch(texts);
            }
            return emptyVectors(texts.size());

        } catch (Exception e) {
            recordRemoteError(e.getMessage());
            log.warn("Embedding 远程调用失败（provider={}）：{}", mode, e.getMessage());
            if ("auto".equals(mode)) {
                return localEmbedBatch(texts);
            }
            return emptyVectors(texts.size());
        }
    }

    /**
     * 真正发起远程嵌入请求。
     */
    private List<List<Float>> remoteEmbedBatch(List<String> texts) throws Exception {
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
    }

    /**
     * 本地特征哈希向量（signed feature hashing）。
     * <p>
     * 对每个 token 取哈希落到 {@code dimensions} 维空间的某一维，符号由哈希的另一位决定，
     * 累加后做 L2 归一化。这样余弦相似度就近似于 token 重叠度。
     * <p>
     * <b>局限</b>：这是词形层面的相似度，不具备语义泛化能力（同义词不会靠近）。
     * 仅用于「远程 embedding 不可用」时保证稠密检索链路不中断。
     */
    public List<Float> localEmbed(String text) {
        int dims = Math.max(1, config.getDimensions());
        float[] vec = new float[dims];
        List<String> tokens = tokenize(text);

        int limit = Math.min(tokens.size(), MAX_LOCAL_TOKENS);
        for (int i = 0; i < limit; i++) {
            int h = tokens.get(i).hashCode();
            // 位混合，改善低位分布（hashCode 低位对短字符串区分度差）
            h ^= (h >>> 16);
            h *= 0x7feb352d;
            h ^= (h >>> 15);

            int idx = Math.floorMod(h, dims);
            // 由哈希另一位派生符号，抵消碰撞带来的系统性正偏
            int sign = ((h >>> 7) & 1) == 0 ? 1 : -1;
            vec[idx] += sign;
        }

        double norm = 0.0;
        for (float v : vec) {
            norm += (double) v * v;
        }

        List<Float> out = new ArrayList<>(dims);
        if (norm == 0.0) {
            for (int i = 0; i < dims; i++) {
                out.add(0.0f);
            }
            return out;
        }
        double inv = 1.0 / Math.sqrt(norm);
        for (float v : vec) {
            out.add((float) (v * inv));
        }
        return out;
    }

    private List<List<Float>> localEmbedBatch(List<String> texts) {
        localFallbackCount.incrementAndGet();
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(text == null || text.isBlank()
                    ? emptyVector()
                    : localEmbed(text));
        }
        return result;
    }

    /**
     * 文本切分：
     * <ul>
     *   <li>ASCII 字母/数字/下划线 —— 按词切分并小写</li>
     *   <li>CJK 字符 —— 输出单字 + 相邻双字组合，双字能保留部分短语信息</li>
     * </ul>
     */
    List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        StringBuilder asciiWord = new StringBuilder();
        List<String> cjkRun = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAsciiWord(asciiWord, tokens);
                cjkRun.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                flushCjkRun(cjkRun, tokens);
                asciiWord.append(Character.toLowerCase(c));
            } else {
                flushAsciiWord(asciiWord, tokens);
                flushCjkRun(cjkRun, tokens);
            }
        }
        flushAsciiWord(asciiWord, tokens);
        flushCjkRun(cjkRun, tokens);
        return tokens;
    }

    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF); // 兼容表意文字
    }

    private void flushAsciiWord(StringBuilder buf, List<String> tokens) {
        if (buf.length() > 0) {
            tokens.add(buf.toString());
            buf.setLength(0);
        }
    }

    private void flushCjkRun(List<String> run, List<String> tokens) {
        if (run.isEmpty()) {
            return;
        }
        tokens.addAll(run);
        for (int i = 0; i + 1 < run.size(); i++) {
            tokens.add(run.get(i) + run.get(i + 1));
        }
        run.clear();
    }

    private String resolveMode() {
        String p = config.getProvider();
        if (p == null || p.isBlank()) {
            return "auto";
        }
        String lower = p.toLowerCase(Locale.ROOT).trim();
        return switch (lower) {
            case "remote", "local", "auto" -> lower;
            default -> "auto";
        };
    }

    private void recordRemoteError(String message) {
        remoteFailureCount.incrementAndGet();
        this.lastRemoteError = message;
        this.lastRemoteErrorAt = System.currentTimeMillis();
    }

    private List<List<Float>> emptyVectors(int count) {
        List<List<Float>> fallback = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            fallback.add(Collections.emptyList());
        }
        return fallback;
    }

    private List<Float> emptyVector() {
        return Collections.emptyList();
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
