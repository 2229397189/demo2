package com.agi.assistant.service.evaluation.llm;

import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link LlmJudge} 的默认实现：通过 {@link WebClient} 调用 OpenAI 兼容的
 * {@code /chat/completions} 端点（当前项目指向智谱 GLM）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>每个方法各自 try-catch，异常只记 {@code log.warn}，返回「不可用」语义的空值，
 *       <b>绝不向上抛异常</b>（一次评测要跑几十上百条 query，单点异常不能击穿整条链路）。</li>
 *   <li>通过 {@link #isLastCallSucceeded()} 暴露「最近一次 LLM 调用是否成功」，
 *       供上层判断该指标是否应记为 {@code -1.0}（绝不编造分数）。</li>
 *   <li>JSON 解析健壮：容忍 Markdown 代码块包裹、前后夹带解释文字（正则截取第一个
 *       {@code '['} 到最后一个 {@code ']'}）。</li>
 * </ul>
 *
 * @author Alex
 */
@Slf4j
@Service
public class WebClientLlmJudge implements LlmJudge {

    /** 通用补全的最大输出 token。 */
    private static final int COMPLETE_MAX_TOKENS = 1024;

    /** claim 分解的最大输出 token（声明列表可能较长）。 */
    private static final int DECOMPOSE_MAX_TOKENS = 1024;

    /** 布尔判定（true/false）的最大输出 token，留足余量以防模型多输出几个词。 */
    private static final int JUDGE_MAX_TOKENS = 16;

    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;

    /** 反向生成问题的最大输出 token，对应 evaluation.ragas.relevancy-max-tokens。 */
    private final int relevancyMaxTokens;

    /** 最近一次 LLM 调用是否成功（供上层判定不可用 → -1.0）。 */
    private volatile boolean lastCallSucceeded = false;

    public WebClientLlmJudge(WebClient openAiWebClient,
                             OpenAIConfig openAIConfig,
                             ObjectMapper objectMapper,
                             @Value("${evaluation.ragas.relevancy-max-tokens:512}") int relevancyMaxTokens) {
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
        this.objectMapper = objectMapper;
        this.relevancyMaxTokens = relevancyMaxTokens;
    }

    /**
     * 最近一次 LLM 调用是否成功。
     *
     * @return true 表示最近一次调用拿到了非空且可解析的响应
     */
    @Override
    public boolean isLastCallSucceeded() {
        return lastCallSucceeded;
    }

    // ──────────────────────────────────────────────────────────────
    //  LlmJudge 实现
    // ──────────────────────────────────────────────────────────────

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String content = chat(systemPrompt, userPrompt, COMPLETE_MAX_TOKENS, 0.0);
        return content == null ? "" : content;
    }

    @Override
    public List<String> decomposeClaims(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String system = "你是一个严谨的信息抽取助手，只输出 JSON。";
        String user = """
                请把下面这段文本拆解为若干「原子事实声明」：每条声明只包含一个可独立核验的事实，
                不要包含连接词堆叠的多重断言，也不要加入原文没有的信息。
                只返回一个 JSON 字符串数组，不要输出任何解释文字、不要使用 Markdown 代码块。
                示例：["声明一","声明二"]

                文本：
                %s
                """.formatted(truncate(text, 4000));

        String content = chat(system, user, DECOMPOSE_MAX_TOKENS, 0.0);
        if (content == null) {
            return List.of();
        }
        List<String> claims = parseStringArray(content);
        if (claims == null) {
            // 拿到响应但无法解析为字符串数组 → 视为不可用
            lastCallSucceeded = false;
            return List.of();
        }
        return claims;
    }

    @Override
    public boolean isSupported(String claim, String context) {
        if (claim == null || claim.isBlank()) {
            return false;
        }
        String system = "你是一个严格的事实核验员，只回答 true 或 false。";
        String user = """
                请判断下面的「声明」是否能被「上下文」中的信息所支持。
                若上下文提供了足够支撑该声明的事实即回答 true；若上下文没有相关信息或与之矛盾则回答 false。
                只回答 true 或 false，不要输出其他任何内容。

                上下文：
                %s

                声明：
                %s
                """.formatted(truncate(context, 6000), truncate(claim, 1000));

        String content = chat(system, user, JUDGE_MAX_TOKENS, 0.0);
        return parseBoolean(content);
    }

    @Override
    public boolean isRelevant(String question, String chunk) {
        if (question == null || question.isBlank() || chunk == null || chunk.isBlank()) {
            return false;
        }
        String system = "你是一个严格的相关性判定员，只回答 true 或 false。";
        String user = """
                请判断下面的「文档片段」是否与「问题」相关，即该片段是否有助于回答该问题。
                只回答 true 或 false，不要输出其他任何内容。

                问题：
                %s

                文档片段：
                %s
                """.formatted(truncate(question, 1000), truncate(chunk, 6000));

        String content = chat(system, user, JUDGE_MAX_TOKENS, 0.0);
        return parseBoolean(content);
    }

    @Override
    public List<String> reverseGenerateQuestions(String answer, int n) {
        if (answer == null || answer.isBlank() || n <= 0) {
            return List.of();
        }
        String system = "你是一个严谨的提问生成助手，只输出 JSON。";
        String user = """
                请根据下面的「答案」，生成 %d 个该答案能够回答的问题，问题应聚焦答案所表达的信息。
                只返回一个 JSON 字符串数组，不要输出任何解释文字、不要使用 Markdown 代码块。
                示例：["问题一","问题二"]

                答案：
                %s
                """.formatted(n, truncate(answer, 4000));

        String content = chat(system, user, relevancyMaxTokens, 0.0);
        if (content == null) {
            return List.of();
        }
        List<String> questions = parseStringArray(content);
        if (questions == null) {
            lastCallSucceeded = false;
            return List.of();
        }
        return questions;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部：LLM 调用与解析
    // ──────────────────────────────────────────────────────────────

    /**
     * 发起一次非流式 chat 补全。
     *
     * @return 回复正文；调用失败或正文为空时返回 {@code null}，并将
     *         {@link #lastCallSucceeded} 置为 false
     */
    private String chat(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        try {
            // 使用可变 Map：OpenAIConfig.applyThinking 需要就地写入 thinking 字段
            Map<String, Object> body = new HashMap<>();
            body.put("model", openAIConfig.getModel());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                    Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)));
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("stream", false);
            openAIConfig.applyThinking(body);

            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(openAIConfig.getTimeout()));

            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                // 空正文：可能是被 token 上限截断、命中思维链或响应格式异常
                lastCallSucceeded = false;
                log.warn("LlmJudge 返回空内容（可能被 token 上限截断或命中思维链）");
                return null;
            }
            lastCallSucceeded = true;
            return content;
        } catch (Exception e) {
            lastCallSucceeded = false;
            log.warn("LlmJudge 调用失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 {@code /chat/completions} 响应中提取 {@code choices[0].message.content}。
     */
    private String extractContent(String response) {
        if (response == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.at("/choices/0/message/content").asText("").trim();
        } catch (Exception e) {
            log.warn("LlmJudge 解析 LLM 响应失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 健壮解析「JSON 字符串数组」响应。
     * <p>
     * 容忍 ```json 代码块包裹、前后夹带解释文字：截取第一个 {@code '['} 到最后一个 {@code ']'}。
     *
     * @return 解析出的非空字符串列表；解析失败时返回 {@code null}（用于区分「空数组」）
     */
    private List<String> parseStringArray(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("LlmJudge 响应中未找到 JSON 数组：{}", truncate(content, 200));
            return null;
        }
        String json = content.substring(start, end + 1);
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (raw == null) {
                return null;
            }
            List<String> result = new ArrayList<>(raw.size());
            for (String item : raw) {
                if (item != null && !item.isBlank()) {
                    result.add(item.trim());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("LlmJudge 无法解析 JSON 字符串数组：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 严格解析布尔判定，容忍 {@code true/false/yes/no/是/否/支持/不支持} 等表述。
     *
     * @return 解析出的布尔值；无法解析时返回 {@code false}
     */
    private boolean parseBoolean(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String c = content.toLowerCase(Locale.ROOT).trim();
        // 先判否定，避免「不支持」命中「支持」
        if (c.contains("false") || c.contains("untrue")
                || c.contains("不支持") || c.contains("不正确") || c.contains("不是")
                || c.contains("否") || c.contains("no")) {
            return false;
        }
        if (c.contains("true") || c.contains("yes")
                || c.contains("支持") || c.contains("是")) {
            return true;
        }
        return false;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
