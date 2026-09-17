package com.agi.assistant.service.agent;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.TaskStatus;
import com.agi.assistant.model.enums.ToolStatus;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ReAct (Reasoning + Acting) engine.
 * <p>
 * Implements the ReAct loop: Thought -> Action -> Observation -> Thought -> ...
 * Supports multi-round reasoning, tool calling, and intermediate result caching.
 */
@Slf4j
@Service
public class ReactEngine {

    private static final String REACT_INSTRUCTION =
            "你是一个智能助手，采用 ReAct (Reasoning + Acting) 模式工作。\n" +
            "对于每个问题，你需要：\n" +
            "1. Thought: 分析当前情况，决定下一步行动\n" +
            "2. Action: 执行一个具体的操作（调用下面列出的某个工具）\n" +
            "3. Observation: 观察操作结果\n" +
            "重复以上步骤直到得到最终答案。\n\n" +
            "请严格按以下格式输出：\n" +
            "Thought: [你的思考过程]\n" +
            "Action: [工具名(参数)]\n" +
            "或\n" +
            "Thought: [你的思考过程]\n" +
            "Action: finish(最终答案)\n\n" +
            "参数可以是纯文本（如 query 内容），也可以是 JSON 对象（需要多个参数时用）。\n\n" +
            "可用的操作：\n";

    private final WebClient openAiWebClient;
    private final ToolRegistry toolRegistry;
    private final HybridRetrievalService hybridRetrievalService;
    private final OpenAIConfig openAIConfig;
    private final Map<String, List<ReActStep>> stepCache;
    private final ObjectMapper objectMapper;

    /** 当前请求的用户 ID，用于把 userId 透传给需要它的工具（如 memory_search） */
    private final ThreadLocal<Long> currentUserId = new ThreadLocal<>();

    public ReactEngine(@Lazy WebClient openAiWebClient,
                       ToolRegistry toolRegistry,
                       @Lazy HybridRetrievalService hybridRetrievalService,
                       OpenAIConfig openAIConfig) {
        this.openAiWebClient = openAiWebClient;
        this.toolRegistry = toolRegistry;
        this.hybridRetrievalService = hybridRetrievalService;
        this.openAIConfig = openAIConfig;
        this.stepCache = new LinkedHashMap<>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<ReActStep>> eldest) {
                return size() > 100;
            }
        };
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 动态拼装 ReAct 系统提示词。
     * <p>
     * 修复说明：此前可用动作被<b>硬编码</b>成 search / calculate / lookup / finish
     * 四个词写死在常量里。于是出现一个荒诞的组合：{@link ToolRegistry} 里注册了什么工具，
     * LLM 完全不知道 —— 它只会按写死的名字去叫，注册表再丰富也永远用不上。
     * 现在从注册表实时生成清单，注册即生效。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(REACT_INSTRUCTION);

        List<ToolRegistry.ToolDefinition> tools = toolRegistry.listTools();
        if (tools.isEmpty()) {
            sb.append("- finish(answer): 给出最终答案（当前没有可用工具，请直接作答）\n");
            return sb.toString();
        }

        for (ToolRegistry.ToolDefinition tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ")
                    .append(tool.getDescription())
                    .append("\n");
        }

        // 内置兜底动作：不经注册表，由本类直接实现
        sb.append("- search(query): 搜索知识库（等价于 knowledge_search）\n");
        sb.append("- lookup(term): 查询术语定义（等价于 knowledge_search）\n");
        sb.append("- calculate(expression): 计算数学表达式\n");
        sb.append("- finish(answer): 给出最终答案\n");
        return sb.toString();
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Run the ReAct loop for a given query.
     * <p>
     * Iterates through the Thought -> Action -> Observation cycle up to
     * maxIterations times or until a finish action is reached.
     *
     * @param query         the user query
     * @param maxIterations maximum number of reasoning iterations
     * @return the final answer from the ReAct loop
     */
    public String run(String query, int maxIterations) {
        return run(query, maxIterations, null);
    }

    /**
     * Run the ReAct loop for a given query on behalf of a specific user.
     * <p>
     * userId 会被透传给需要用户维度的工具（如 memory_search）。
     * 用 ThreadLocal 是因为 ReAct 的 Action 语法里没有位置放 userId。
     *
     * @param query         the user query
     * @param maxIterations maximum number of reasoning iterations
     * @param userId        the calling user's id, may be null
     * @return the final answer from the ReAct loop
     */
    public String run(String query, int maxIterations, Long userId) {
        if (query == null || query.isBlank()) {
            return "";
        }

        currentUserId.set(userId);
        try {
            return runLoop(query, maxIterations);
        } finally {
            // 线程池复用线程，必须清理，否则下一个请求会继承上一个用户的身份
            currentUserId.remove();
        }
    }

    private String runLoop(String query, int maxIterations) {
        int iterations = Math.max(1, Math.min(maxIterations, 10));
        log.info("Starting ReAct loop: query='{}', maxIterations={}",
                query.length() > 50 ? query.substring(0, 50) + "..." : query, iterations);

        List<ReActStep> steps = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        context.append("问题: ").append(query).append("\n\n");

        for (int i = 0; i < iterations; i++) {
            log.debug("ReAct iteration {}/{}", i + 1, iterations);

            // 1. Think
            String thought = think(query, context.toString());
            if (thought == null || thought.isBlank()) {
                log.warn("Empty thought at iteration {}, stopping", i + 1);
                break;
            }

            steps.add(ReActStep.builder()
                    .iteration(i + 1)
                    .type("thought")
                    .content(thought)
                    .build());

            context.append("Thought: ").append(thought).append("\n");

            // 2. Parse action from thought
            Map<String, String> action = parseAction(thought);
            if (action == null) {
                // Try to get action from next LLM call
                String actionStr = think(query, context.toString() + "\n请给出下一步 Action:");
                action = parseAction(actionStr);
                if (action == null) {
                    log.debug("No action parsed at iteration {}, checking for finish", i + 1);
                    if (thought.toLowerCase().contains("finish") || thought.toLowerCase().contains("最终答案")) {
                        String answer = extractFinishAnswer(thought);
                        if (answer != null) {
                            cacheSteps(query, steps);
                            return answer;
                        }
                    }
                    continue;
                }
                context.append("Action: ").append(actionStr).append("\n");
            } else {
                context.append("Action: ").append(action.get("raw")).append("\n");
            }

            // 3. Act
            String actionName = action.get("name");
            String actionParam = action.get("param");

            // Check for finish action
            if ("finish".equalsIgnoreCase(actionName)) {
                String answer = actionParam != null ? actionParam : extractFinishAnswer(thought);
                steps.add(ReActStep.builder()
                        .iteration(i + 1)
                        .type("finish")
                        .content(answer)
                        .build());

                cacheSteps(query, steps);
                log.info("ReAct loop finished at iteration {}: answer length={}",
                        i + 1, answer != null ? answer.length() : 0);
                return answer != null ? answer : thought;
            }

            String observation = act(actionName, actionParam);
            steps.add(ReActStep.builder()
                    .iteration(i + 1)
                    .type("action")
                    .content(actionName + "(" + actionParam + ")")
                    .build());

            // 4. Observe
            String processedObservation = observe(observation);
            steps.add(ReActStep.builder()
                    .iteration(i + 1)
                    .type("observation")
                    .content(processedObservation)
                    .build());

            context.append("Observation: ").append(processedObservation).append("\n\n");
        }

        // Max iterations reached
        cacheSteps(query, steps);
        log.warn("ReAct loop reached max iterations ({}) without finish action", iterations);
        return "经过多轮推理，未能得出明确结论。最后的思考：" +
                (steps.isEmpty() ? "无" : steps.get(steps.size() - 1).getContent());
    }

    /**
     * Generate a thought based on the current query and context.
     *
     * @param query   the original user query
     * @param context the accumulated reasoning context
     * @return the generated thought text
     */
    public String think(String query, String context) {
        try {
            String systemPrompt = buildSystemPrompt();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAIConfig.getModel());
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", context)
            ));
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 1000);
            // 推理过程本身不需要思维链，反而会挤占 max_tokens 让 Action 被截断
            openAIConfig.applyThinking(requestBody);

            String responseStr = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");

        } catch (Exception e) {
            log.error("Think step failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Execute an action by name and parameters.
     * Attempts to use the ToolRegistry first, then falls back to built-in actions.
     *
     * @param action the action name
     * @param params the action parameters
     * @return the observation (result) of the action
     */
    public String act(String action, String params) {
        if (action == null || action.isBlank()) {
            return "Error: empty action";
        }

        log.debug("Executing action: {}({})", action, params);

        // Try tool registry first
        try {
            Map<String, Object> paramMap = buildToolParams(params);

            Map<String, Object> toolResult = toolRegistry.executeTool(action, paramMap, currentUserId.get());
            if (toolResult != null) {
                // 成功：注册表约定 handler 把可读文本放在 "result"
                Object result = toolResult.get("result");
                if (result != null) {
                    return result.toString();
                }
                // 失败：必须把 error 透出去。
                // 修复前这里只检查 "result"，工具执行失败（返回 error 但不带 result）
                // 会被当成「工具不存在」直接落到下面的内置分支，
                // 最终给 LLM 的观察结果是 "Unknown action: knowledge_search" —— 完全误导。
                Object error = toolResult.get("error");
                if (error != null && toolRegistry.hasTool(action)) {
                    return "Tool [" + action + "] failed: " + error;
                }
            }
        } catch (Exception e) {
            log.debug("Tool registry lookup failed for action [{}]: {}", action, e.getMessage());
        }

        // Built-in actions
        switch (action.toLowerCase()) {
            case "search":
                return executeSearch(params);
            case "calculate":
                return executeCalculate(params);
            case "lookup":
                return executeLookup(params);
            default:
                return "Unknown action: " + action + ". Available: " + availableActionNames();
        }
    }

    /**
     * 把 ReAct 的「单个位置参数」适配成工具需要的参数 Map。
     * <p>
     * ReAct 的 Action 语法只给了一个括号里的字符串，但工具参数不止一个
     * （如 run_code 需要 language + code + timeout）。这里做两层适配：
     * <ol>
     *   <li>参数以 <code>{</code> 开头 → 按 JSON 对象解析并展开，支持多参数；</li>
     *   <li>否则作为纯文本，同时挂到 query / expression / term / code 几个常见键上，
     *       让单参数工具（knowledge_search、calculate 等）都能取到自己要的那个名字。</li>
     * </ol>
     * 另外统一注入 userId（若当前请求有），供 memory_search 这类需要用户维度的工具使用。
     */
    private Map<String, Object> buildToolParams(String params) {
        Map<String, Object> paramMap = new HashMap<>();

        if (params != null && !params.isBlank()) {
            String trimmed = params.trim();
            if (trimmed.startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(trimmed, Map.class);
                    paramMap.putAll(parsed);
                } catch (Exception e) {
                    log.debug("Action params look like JSON but failed to parse, treating as text: {}",
                            e.getMessage());
                }
            }

            if (!paramMap.containsKey("query")) {
                paramMap.put("query", params);
            }
            paramMap.putIfAbsent("expression", params);
            paramMap.putIfAbsent("term", params);
            paramMap.putIfAbsent("code", params);
        }

        if (currentUserId.get() != null) {
            paramMap.putIfAbsent("userId", currentUserId.get());
        }

        return paramMap;
    }

    /**
     * 列出当前真正可用的动作名，用于把「未知动作」的报错变成有用的提示。
     */
    private String availableActionNames() {
        List<String> names = new java.util.ArrayList<>();
        toolRegistry.listTools().forEach(t -> names.add(t.getName()));
        names.add("search");
        names.add("calculate");
        names.add("lookup");
        names.add("finish");
        return String.join(", ", names);
    }

    /**
     * Process an observation result.
     * Truncates very long results and formats for context consumption.
     *
     * @param result the raw observation
     * @return the processed observation
     */
    public String observe(String result) {
        if (result == null) {
            return "No result";
        }

        // Truncate very long results to fit in context
        int maxLen = 2000;
        if (result.length() > maxLen) {
            return result.substring(0, maxLen) + "... [truncated, total " + result.length() + " chars]";
        }
        return result;
    }

    /**
     * Get cached steps for a previously processed query.
     *
     * @param query the query to look up
     * @return the list of steps, or empty list if not cached
     */
    public List<ReActStep> getCachedSteps(String query) {
        return stepCache.getOrDefault(query, Collections.emptyList());
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private Map<String, String> parseAction(String text) {
        if (text == null) {
            return null;
        }

        // Look for "Action: name(params)" pattern
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Action:") || trimmed.startsWith("action:")) {
                String actionPart = trimmed.substring(7).trim();

                // Parse name(params)
                int parenStart = actionPart.indexOf('(');
                int parenEnd = actionPart.lastIndexOf(')');
                if (parenStart > 0 && parenEnd > parenStart) {
                    String name = actionPart.substring(0, parenStart).trim();
                    String param = actionPart.substring(parenStart + 1, parenEnd).trim();

                    Map<String, String> result = new HashMap<>();
                    result.put("name", name);
                    result.put("param", param);
                    result.put("raw", actionPart);
                    return result;
                }
            }
        }

        return null;
    }

    private String extractFinishAnswer(String text) {
        if (text == null) {
            return null;
        }

        // Look for finish(answer) pattern
        int start = text.indexOf("finish(");
        if (start >= 0) {
            int end = text.indexOf(")", start + 7);
            if (end > start) {
                return text.substring(start + 7, end).trim();
            }
        }

        // Look for answer patterns
        String[] markers = {"最终答案:", "答案:", "答案是:", "Answer:", "answer:"};
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx >= 0) {
                return text.substring(idx + marker.length()).trim();
            }
        }

        return null;
    }

    private String executeSearch(String query) {
        if (query == null || query.isBlank()) {
            return "Search query is empty";
        }
        try {
            List<SearchResult> results = hybridRetrievalService.retrieve(query, "HYBRID", 5);
            if (results.isEmpty()) {
                return "No results found for: " + query;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(results.size()).append(" results:\n");
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append(i + 1).append(". [").append(r.getSource()).append("] ");
                sb.append(r.getContent(), 0, Math.min(r.getContent().length(), 200));
                sb.append(" (score: ").append(String.format("%.3f", r.getScore())).append(")\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Search failed: {}", e.getMessage());
            return "Search error: " + e.getMessage();
        }
    }

    private String executeCalculate(String expression) {
        if (expression == null || expression.isBlank()) {
            return "Empty expression";
        }
        try {
            // Simple arithmetic evaluation for basic expressions
            String sanitized = expression.replaceAll("[^0-9+\\-*/().%\\s]", "");
            double result = evaluateSimpleExpression(sanitized);
            return String.valueOf(result);
        } catch (Exception e) {
            return "Calculation error: " + e.getMessage();
        }
    }

    private String executeLookup(String term) {
        if (term == null || term.isBlank()) {
            return "Empty lookup term";
        }
        try {
            List<SearchResult> results = hybridRetrievalService.retrieve(term, "DENSE", 3);
            if (results.isEmpty()) {
                return "No information found for: " + term;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Lookup results for '").append(term).append("':\n");
            for (int i = 0; i < results.size(); i++) {
                sb.append(i + 1).append(". ");
                sb.append(results.get(i).getContent(), 0,
                        Math.min(results.get(i).getContent().length(), 300));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Lookup failed: {}", e.getMessage());
            return "Lookup error: " + e.getMessage();
        }
    }

    private double evaluateSimpleExpression(String expr) {
        // Use a basic recursive descent parser for arithmetic
        return new ExpressionParser(expr.trim()).parse();
    }

    private void cacheSteps(String query, List<ReActStep> steps) {
        stepCache.put(query, new ArrayList<>(steps));
    }

    // ----------------------------------------------------------------
    //  Inner Classes
    // ----------------------------------------------------------------

    /**
     * Represents a single step in the ReAct loop.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReActStep {
        private int iteration;
        private String type;  // "thought", "action", "observation", "finish"
        private String content;
    }

    /**
     * Simple recursive descent expression parser for basic arithmetic.
     */
    private static class ExpressionParser {
        private final String input;
        private int pos;

        ExpressionParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        double parse() {
            double result = parseExpression();
            if (pos < input.length()) {
                throw new RuntimeException("Unexpected character at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double result = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') {
                    pos++;
                    result += parseTerm();
                } else if (c == '-') {
                    pos++;
                    result -= parseTerm();
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseTerm() {
            double result = parseFactor();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') {
                    pos++;
                    result *= parseFactor();
                } else if (c == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) throw new RuntimeException("Division by zero");
                    result /= divisor;
                } else if (c == '%') {
                    pos++;
                    double modulo = parseFactor();
                    if (modulo == 0) throw new RuntimeException("Modulo by zero");
                    result %= modulo;
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseFactor() {
            skipWhitespace();
            if (pos >= input.length()) throw new RuntimeException("Unexpected end of expression");

            // Handle unary minus
            boolean negative = false;
            if (input.charAt(pos) == '-') {
                negative = true;
                pos++;
                skipWhitespace();
            }

            double result;
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++; // skip '('
                result = parseExpression();
                if (pos < input.length() && input.charAt(pos) == ')') {
                    pos++; // skip ')'
                } else {
                    throw new RuntimeException("Missing closing parenthesis");
                }
            } else {
                int start = pos;
                while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                if (start == pos) throw new RuntimeException("Expected number at position " + pos);
                result = Double.parseDouble(input.substring(start, pos));
            }

            skipWhitespace();
            return negative ? -result : result;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
