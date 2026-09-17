package com.agi.assistant.service.memory;

import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner 计划工厂。
 * <p>
 * 负责在 ReAct 循环开始前，把用户问题分解为一串「有序、可执行」的步骤，
 * 供 {@link RuntimeStateMemory#updatePlan(String, List)} 写入运行态的 Planner State。
 * <p>
 * 修复背景：此前 {@code RuntimeStateMemory} 的三个计划写方法
 * （{@code updatePlan} / {@code advancePlanStep} / {@code completePlan}）全项目零调用，
 * 而读侧（{@code ContextAssembly} / {@code assembleRuntimeContext}）却在读 ——
 * 写侧从不写、读侧拼命读，导致「当前计划状态」段落恒为空。本类提供了写侧的语义来源。
 * <p>
 * 两条路径：
 * <ul>
 *   <li><b>LLM 路径</b>：{@code useLlm=true} 且 LLM 可用时，让 LLM 返回一个 JSON 字符串数组，
 *       解析健壮（容忍 ```json 包裹与前后解释文字：截取第一个 {@code '['} 到最后一个 {@code ']'}）；</li>
 *   <li><b>启发式回退</b>：{@code useLlm=false}、LLM 不可用或解析失败时，返回一组真实可执行的
 *       步骤描述（绝不返回空列表或 {@code ["step1","step2"]} 之类的占位符）。</li>
 * </ul>
 * 实现风格对齐 {@code service/evaluation/llm/WebClientLlmJudge}：每个入口自 try-catch，
 * 异常只记 {@code log.warn} 并回退，绝不向上抛。
 *
 * @author 寇豆码 (Alex)
 */
@Slf4j
@Service
public class PlanFactory {

    /** LLM 计划分解的最大输出 token（3~6 步的短句足够）。 */
    private static final int PLAN_MAX_TOKENS = 512;

    /** LLM 计划分解的系统提示词。 */
    private static final String PLAN_SYSTEM_PROMPT = "你是一个严谨的任务规划助手，只输出 JSON。";

    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanFactory(@Lazy WebClient openAiWebClient, OpenAIConfig openAIConfig) {
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
    }

    // ──────────────────────────────────────────────────────────────
    //  对外入口
    // ──────────────────────────────────────────────────────────────

    /**
     * 为一个用户问题构造有序的执行计划。
     *
     * @param query  用户问题，可为 null/空（此时回退为通用计划）
     * @param useLlm 是否允许走 LLM 分解；true 但 LLM 不可用/解析失败时自动回退
     * @return 非空的有序步骤列表，元素均为非空字符串
     */
    public List<String> build(String query, boolean useLlm) {
        if (useLlm && isLlmAvailable()) {
            List<String> llmSteps = tryLlmPlan(query);
            if (llmSteps != null && !llmSteps.isEmpty()) {
                return llmSteps;
            }
            log.debug("LLM 计划分解不可用或解析失败，改用启发式回退计划");
        }
        return fallbackPlan(query);
    }

    // ──────────────────────────────────────────────────────────────
    //  LLM 路径
    // ──────────────────────────────────────────────────────────────

    /**
     * LLM 是否可用：需要 WebClient 与已配置的 API Key。
     * <p>
     * 这是最廉价的可用性门控（避免把一次注定 401 的请求发出去）；
     * 真正的失败仍由 {@link #tryLlmPlan(String)} 的 try-catch 兜底。
     */
    private boolean isLlmAvailable() {
        return openAiWebClient != null
                && openAIConfig != null
                && openAIConfig.getApiKey() != null
                && !openAIConfig.getApiKey().isBlank();
    }

    /**
     * 尝试用 LLM 把问题分解为步骤。
     *
     * @return 解析出的非空步骤列表；任何失败（网络 / 解析 / 空结果）均返回 {@code null}
     */
    private List<String> tryLlmPlan(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            String userPrompt = """
                    请把下面的「用户问题」分解为若干「有序的执行步骤」：步骤应当具体、可执行，
                    并覆盖从理解问题到给出答案的完整过程。
                    要求：
                    1. 每个步骤是一句简短的行动描述，不要把多个动作挤进同一步；
                    2. 步骤数量控制在 3 到 6 个之间；
                    3. 只返回一个 JSON 字符串数组，不要输出任何解释文字、不要使用 Markdown 代码块。
                    示例：["理解问题意图","检索相关知识","综合推理并校验","生成最终答案"]

                    用户问题：
                    %s
                    """.formatted(query);

            // 可变 Map：OpenAIConfig.applyThinking 需就地写入 thinking 字段
            Map<String, Object> body = new HashMap<>();
            body.put("model", openAIConfig.getModel());
            body.put("messages", List.of(
                    Map.of("role", "system", "content", PLAN_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userPrompt)));
            body.put("temperature", 0.2);
            body.put("max_tokens", PLAN_MAX_TOKENS);
            body.put("stream", false);
            openAIConfig.applyThinking(body);

            long timeoutSeconds = openAIConfig.getTimeout() > 0 ? openAIConfig.getTimeout() : 120L;
            String response = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            String content = extractContent(response);
            List<String> steps = parseStringArray(content);
            if (steps == null || steps.isEmpty()) {
                log.warn("PlanFactory 未能从 LLM 响应中解析出计划步骤");
                return null;
            }
            return steps;
        } catch (Exception e) {
            log.warn("PlanFactory LLM 计划分解失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 {@code /chat/completions} 响应中提取 {@code choices[0].message.content}。
     *
     * @return 回复正文；无法解析时返回 {@code null}
     */
    private String extractContent(String response) {
        if (response == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText("");
            return content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            log.warn("PlanFactory 解析 LLM 响应失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 健壮解析「JSON 字符串数组」响应。
     * <p>
     * 容忍 ```json 代码块包裹与前后夹带的解释文字：截取第一个 {@code '['} 到最后一个 {@code ']'}。
     *
     * @return 解析出的非空字符串列表；解析失败时返回 {@code null}（与「空数组」区分）
     */
    private List<String> parseStringArray(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("PlanFactory 响应中未找到 JSON 数组：{}", truncate(content, 200));
            return null;
        }
        String json = content.substring(start, end + 1);
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            if (raw == null) {
                return null;
            }
            List<String> steps = new ArrayList<>(raw.size());
            for (String item : raw) {
                if (item != null && !item.isBlank()) {
                    steps.add(item.trim());
                }
            }
            return steps;
        } catch (Exception e) {
            log.warn("PlanFactory 无法解析 JSON 字符串数组：{}", e.getMessage());
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  启发式回退
    // ──────────────────────────────────────────────────────────────

    /**
     * 启发式回退计划：不依赖任何外部服务，返回一组真实可执行的步骤描述。
     * <p>
     * 刻意不返回空列表、也不返回 {@code ["step1","step2"]} 之类占位符 —— 那样即使写进了
     * Planner State，展示给 LLM 的上下文依然毫无信息量，等于没做。
     *
     * @param query 用户问题，用于把第一步具体化（可为 null/空）
     * @return 4 步的有序计划，元素均非空
     */
    private List<String> fallbackPlan(String query) {
        List<String> steps = new ArrayList<>();
        steps.add("理解问题意图" + summarizeSubject(query));
        steps.add("检索与问题相关的知识与上下文");
        steps.add("逐步推理并对中间结论进行校验");
        steps.add("整合推理结果，生成结构化的最终答案");
        return steps;
    }

    /**
     * 把用户问题压缩成一句可拼进步骤描述的主题片段。
     */
    private String summarizeSubject(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String oneLine = query.trim().replace("\n", " ").replace("\r", " ");
        if (oneLine.length() > 40) {
            oneLine = oneLine.substring(0, 40) + "…";
        }
        return "：" + oneLine;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
