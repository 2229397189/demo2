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

    private static final String REACT_SYSTEM_PROMPT =
            "你是一个智能助手，采用 ReAct (Reasoning + Acting) 模式工作。\n" +
            "对于每个问题，你需要：\n" +
            "1. Thought: 分析当前情况，决定下一步行动\n" +
            "2. Action: 执行一个具体的操作（搜索、计算、查询工具等）\n" +
            "3. Observation: 观察操作结果\n" +
            "重复以上步骤直到得到最终答案。\n\n" +
            "可用的操作格式：\n" +
            "- search(query): 搜索知识库\n" +
            "- calculate(expression): 计算数学表达式\n" +
            "- lookup(term): 查询术语定义\n" +
            "- finish(answer): 给出最终答案\n\n" +
            "请严格按以下格式输出：\n" +
            "Thought: [你的思考过程]\n" +
            "Action: [操作名称(参数)]\n" +
            "或\n" +
            "Thought: [你的思考过程]\n" +
            "Action: finish(最终答案)";

    private final WebClient openAiWebClient;
    private final ToolRegistry toolRegistry;
    private final HybridRetrievalService hybridRetrievalService;
    private final OpenAIConfig openAIConfig;
    private final Map<String, List<ReActStep>> stepCache;
    private final ObjectMapper objectMapper;

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
        if (query == null || query.isBlank()) {
            return "";
        }

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
            String prompt = REACT_SYSTEM_PROMPT + "\n\n" + context;

            Map<String, Object> requestBody = Map.of(
                    "model", openAIConfig.getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", REACT_SYSTEM_PROMPT),
                            Map.of("role", "user", "content", context)
                    ),
                    "temperature", 0.3,
                    "max_tokens", 1000
            );

            String responseStr = openAiWebClient.post()
                    .uri("/v1/chat/completions")
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
            Map<String, Object> paramMap = new HashMap<>();
            if (params != null && !params.isBlank()) {
                paramMap.put("query", params);
            }

            Map<String, Object> toolResult = toolRegistry.executeTool(action, paramMap);
            if (toolResult != null && toolResult.containsKey("result")) {
                return toolResult.get("result").toString();
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
                return "Unknown action: " + action + ". Available: search, calculate, lookup, finish";
        }
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
