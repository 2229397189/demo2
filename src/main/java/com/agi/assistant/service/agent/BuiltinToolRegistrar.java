package com.agi.assistant.service.agent;

import com.agi.assistant.model.dto.SandboxExecuteRequest;
import com.agi.assistant.model.dto.SandboxExecuteResponse;
import com.agi.assistant.model.dto.ToolResult;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.ToolRiskLevel;
import com.agi.assistant.service.SandboxService;
import com.agi.assistant.service.memory.LongTermMemory;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.agi.assistant.service.rag.WebSearchService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具注册器。
 * <p>
 * 修复说明：本类解决的是一个「整个子系统是死代码」的问题 ——
 * {@link ToolRegistry} 有完整的注册/查表/风险分级/执行能力，
 * {@link ReactEngine} 也会在每一步 Action 里先查注册表，
 * 但全项目<b>没有任何一处调用过 {@code registerTool}</b>，
 * 于是每次 {@code executeTool} 都返回 "Tool not found"，
 * ReAct 永远只能退回到内置的 search/calculate/lookup 三个硬编码分支。
 * 换句话说：工具调用框架搭好了，工具箱是空的。
 * <p>
 * 这里把项目里已经存在的能力包装成工具注册进去：
 * <ul>
 *   <li>{@code knowledge_search} —— 混合检索（向量 + BM25 + 图谱 + RRF 融合）</li>
 *   <li>{@code web_search} —— 联网搜索</li>
 *   <li>{@code memory_search} —— 长期记忆召回</li>
 *   <li>{@code current_time} —— 当前时间（LLM 自己不知道「现在」）</li>
 *   <li>{@code calculate} —— 四则运算</li>
 *   <li>{@code run_code} —— 沙箱执行代码（WARN 级，有副作用）</li>
 * </ul>
 * <p>
 * 风险分级原则（对应安全治理里的「动作确认分级」）：
 * 只读操作 → SAFE；会产生外部副作用/消耗资源的操作 → WARN。
 */
@Slf4j
@Component
public class BuiltinToolRegistrar {

    /** 参数名常量 */
    private static final String P_QUERY = "query";
    private static final String P_TOP_K = "topK";
    private static final String P_USER_ID = "userId";
    private static final String P_LANGUAGE = "language";
    private static final String P_CODE = "code";
    private static final String P_TIMEOUT = "timeout";
    private static final String P_EXPRESSION = "expression";
    private static final String P_CONFIRMED = "confirmed";

    private static final int DEFAULT_TOP_K = 5;

    private final ToolRegistry toolRegistry;
    private final HybridRetrievalService hybridRetrievalService;
    private final WebSearchService webSearchService;
    private final LongTermMemory longTermMemory;
    private final SandboxService sandboxService;

    public BuiltinToolRegistrar(ToolRegistry toolRegistry,
                                @Lazy HybridRetrievalService hybridRetrievalService,
                                @Lazy WebSearchService webSearchService,
                                @Lazy LongTermMemory longTermMemory,
                                @Lazy SandboxService sandboxService) {
        this.toolRegistry = toolRegistry;
        this.hybridRetrievalService = hybridRetrievalService;
        this.webSearchService = webSearchService;
        this.longTermMemory = longTermMemory;
        this.sandboxService = sandboxService;
    }

    @PostConstruct
    public void registerBuiltinTools() {
        registerKnowledgeSearch();
        registerWebSearch();
        registerMemorySearch();
        registerCurrentTime();
        registerCalculate();
        registerRunCode();

        log.info("Builtin tools registered: count={}, tools={}",
                toolRegistry.size(),
                toolRegistry.listTools().stream().map(ToolRegistry.ToolDefinition::getName).toList());
    }

    // ----------------------------------------------------------------
    //  Tool implementations
    // ----------------------------------------------------------------

    /**
     * 强类型注册薄封装，委托到
     * {@link ToolRegistry#registerTool(String, String, ToolRiskLevel, ToolHandler)}。
     * <p>
     * 存在此封装的原因：{@link ToolRegistry} 同时保留了旧的 {@code Function<Map,Map>}
     * 重载与新的 {@link ToolHandler} 重载，二者形状相同，直接传隐式 lambda 会触发
     * 重载歧义；这里先把 handler 显式定型为 {@link ToolHandler} 再转发，既消除歧义，
     * 又明确表达「使用强类型 handler」的意图。
     */
    private void register(String name, String description, ToolRiskLevel riskLevel, ToolHandler handler) {
        toolRegistry.registerTool(name, description, riskLevel, handler);
    }

    private void registerKnowledgeSearch() {
        register(
                "knowledge_search",
                "在本地知识库中做混合检索（向量 + 关键词 + 图谱），返回最相关的文档片段。"
                        + "参数：query（检索词，必填）、topK（返回条数，默认 5）",
                ToolRiskLevel.SAFE,
                params -> {
                    String query = strParam(params, P_QUERY);
                    if (query == null || query.isBlank()) {
                        return ToolResult.failure("knowledge_search", "missing required parameter: query");
                    }
                    int topK = intParam(params, P_TOP_K, DEFAULT_TOP_K);

                    List<SearchResult> results = hybridRetrievalService.retrieve(query, "HYBRID", topK);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", results.size());
                    data.put("items", toItemList(results));
                    return ToolResult.success("knowledge_search", formatSearchResults(results, query), data);
                });
    }

    private void registerWebSearch() {
        register(
                "web_search",
                "联网搜索实时信息（新闻、天气、股价、最新动态等）。"
                        + "参数：query（检索词，必填）、topK（返回条数，默认 5）",
                ToolRiskLevel.SAFE,
                params -> {
                    String query = strParam(params, P_QUERY);
                    if (query == null || query.isBlank()) {
                        return ToolResult.failure("web_search", "missing required parameter: query");
                    }
                    int topK = intParam(params, P_TOP_K, DEFAULT_TOP_K);

                    List<SearchResult> results = webSearchService.search(query, topK);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", results.size());
                    data.put("items", toItemList(results));
                    return ToolResult.success("web_search", formatSearchResults(results, query), data);
                });
    }

    private void registerMemorySearch() {
        register(
                "memory_search",
                "检索用户的长期记忆（偏好、习惯、已知事实）。"
                        + "参数：query（检索词，必填）、userId（用户 ID，必填）、topK（返回条数，默认 5）",
                ToolRiskLevel.SAFE,
                params -> {
                    String query = strParam(params, P_QUERY);
                    Long userId = longParam(params, P_USER_ID);
                    if (query == null || query.isBlank()) {
                        return ToolResult.failure("memory_search", "missing required parameter: query");
                    }
                    if (userId == null) {
                        return ToolResult.failure("memory_search", "missing required parameter: userId");
                    }
                    int topK = intParam(params, P_TOP_K, DEFAULT_TOP_K);

                    List<String> memories = longTermMemory.recallMemory(userId, query, topK);
                    StringBuilder sb = new StringBuilder();
                    if (memories.isEmpty()) {
                        sb.append("没有检索到与该用户相关的长期记忆。");
                    } else {
                        sb.append("检索到 ").append(memories.size()).append(" 条相关记忆：\n");
                        for (int i = 0; i < memories.size(); i++) {
                            sb.append(i + 1).append(". ").append(memories.get(i)).append("\n");
                        }
                    }

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("count", memories.size());
                    data.put("items", memories);
                    return ToolResult.success("memory_search", sb.toString(), data);
                });
    }

    private void registerCurrentTime() {
        register(
                "current_time",
                "获取服务器当前日期时间与星期。参数：无",
                ToolRiskLevel.SAFE,
                params -> {
                    LocalDateTime now = LocalDateTime.now();
                    String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    String text = "当前时间：" + formatted
                            + "（" + switch (now.getDayOfWeek()) {
                                case MONDAY -> "星期一";
                                case TUESDAY -> "星期二";
                                case WEDNESDAY -> "星期三";
                                case THURSDAY -> "星期四";
                                case FRIDAY -> "星期五";
                                case SATURDAY -> "星期六";
                                case SUNDAY -> "星期日";
                            } + "）";

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("iso", now.toString());
                    return ToolResult.success("current_time", text, data);
                });
    }

    private void registerCalculate() {
        register(
                "calculate",
                "计算四则运算表达式，支持 + - * / % 与括号。参数：expression（表达式，必填）",
                ToolRiskLevel.SAFE,
                params -> {
                    String expression = strParam(params, P_EXPRESSION);
                    if (expression == null || expression.isBlank()) {
                        return ToolResult.failure("calculate", "missing required parameter: expression");
                    }
                    String sanitized = expression.replaceAll("[^0-9+\\-*/().%\\s]", "");
                    double value = new ArithmeticEvaluator(sanitized).evaluate();

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("value", value);
                    data.put("expression", sanitized.trim());
                    return ToolResult.success("calculate", String.valueOf(value), data);
                });
    }

    private void registerRunCode() {
        register(
                "run_code",
                "在隔离沙箱中执行代码并返回输出。"
                        + "参数：language（python/javascript/java，必填）、code（源码，必填）、timeout（秒，默认 30）",
                ToolRiskLevel.WARN,
                params -> {
                    String language = strParam(params, P_LANGUAGE);
                    String code = strParam(params, P_CODE);
                    if (language == null || language.isBlank()) {
                        return ToolResult.failure("run_code", "missing required parameter: language");
                    }
                    if (code == null || code.isBlank()) {
                        return ToolResult.failure("run_code", "missing required parameter: code");
                    }
                    int timeout = intParam(params, P_TIMEOUT, 30);

                    // 确认门槛：确认状态从入参读取，绝不由工具侧硬编码为 true。
                    // 修复前这里写死 setConfirmed(true)，等于架空了 sandbox.require-confirm：
                    // 任何 LLM 生成的 run_code 调用都会绕过确认直接执行沙箱代码。
                    // 现在只有调用方显式传入 confirmed=true 才会通过门槛。
                    boolean confirmed = boolParam(params, P_CONFIRMED, false);

                    SandboxExecuteRequest request = new SandboxExecuteRequest();
                    request.setLanguage(normalizeLanguage(language));
                    request.setCode(code);
                    request.setTimeout(timeout);
                    request.setConfirmed(confirmed);

                    SandboxExecuteResponse response;
                    try {
                        response = sandboxService.execute(request);
                    } catch (IllegalArgumentException e) {
                        // SandboxServiceImpl 的确认门槛拒绝：如实转成结构化失败，绝不吞掉、绝不自动确认
                        log.warn("run_code rejected by sandbox confirmation gate: {}", e.getMessage());
                        return ToolResult.failure("run_code",
                                "沙箱执行被拒绝，需要用户确认后才能执行（请在参数中携带 confirmed=true）："
                                        + e.getMessage());
                    } catch (Exception e) {
                        // 其它沙箱异常也结构化降级，不向上抛
                        log.error("run_code sandbox execution failed: {}", e.getMessage(), e);
                        return ToolResult.failure("run_code", "沙箱执行失败：" + e.getMessage());
                    }

                    StringBuilder sb = new StringBuilder();
                    if (response.getOutput() != null && !response.getOutput().isBlank()) {
                        sb.append(response.getOutput());
                    }
                    if (response.getError() != null && !response.getError().isBlank()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append("[stderr] ").append(response.getError());
                    }
                    if (sb.length() == 0) {
                        sb.append("(无输出)");
                    }

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("exitCode", response.getExitCode());
                    data.put("executionTime", response.getExecutionTime());
                    data.put("language", request.getLanguage());
                    return ToolResult.success("run_code", sb.toString(), data);
                });
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private String formatSearchResults(List<SearchResult> results, String query) {
        if (results == null || results.isEmpty()) {
            return "没有检索到与「" + query + "」相关的内容。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("检索到 ").append(results.size()).append(" 条与「").append(query).append("」相关的内容：\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ");
            if (r.getTitle() != null && !r.getTitle().isBlank()) {
                sb.append("[").append(r.getTitle()).append("] ");
            }
            String content = r.getContent() == null ? "" : r.getContent();
            sb.append(content, 0, Math.min(content.length(), 400));
            if (r.getSource() != null) {
                sb.append("（来源：").append(r.getSource()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> toItemList(List<SearchResult> results) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (results == null) {
            return items;
        }
        for (SearchResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", r.getTitle());
            item.put("content", r.getContent());
            item.put("source", r.getSource());
            item.put("score", r.getScore());
            items.add(item);
        }
        return items;
    }

    private String normalizeLanguage(String language) {
        String lower = language.trim().toLowerCase();
        return switch (lower) {
            case "py", "python3" -> "python";
            case "js", "node", "nodejs" -> "javascript";
            case "java8", "jdk" -> "java";
            default -> lower;
        };
    }

    /**
     * Robustly parse a boolean parameter.
     * <p>
     * Accepts {@link Boolean} values and case-insensitive {@code "true"}/{@code "false"}
     * strings (trimmed); numbers are treated as {@code != 0}. Any other value — including
     * {@code null} — falls back to {@code defaultValue}. Callers pass {@code false} for the
     * sandbox confirmation flag, so malformed input is treated as "not confirmed".
     *
     * @param params       parameter map, may be null
     * @param key          parameter key
     * @param defaultValue value returned when the parameter is absent or unparseable
     * @return parsed boolean
     */
    private boolean boolParam(Map<String, Object> params, String key, boolean defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        return defaultValue;
    }

    private String strParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Long longParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 极简四则运算求值器（递归下降），只支持 + - * / % 与括号。
     * <p>
     * 之所以不用脚本引擎（如 Nashorn / SpEL）：那段表达式来源于 LLM 输出，
     * 交给通用表达式引擎等于给它一个执行环境，越权风险不值得。
     */
    private static final class ArithmeticEvaluator {
        private final String input;
        private int pos;

        ArithmeticEvaluator(String input) {
            this.input = input == null ? "" : input;
        }

        double evaluate() {
            if (input.isBlank()) {
                throw new IllegalArgumentException("expression is empty");
            }
            double value = parseExpression();
            if (pos < input.length()) {
                throw new IllegalArgumentException("unexpected token at position " + pos);
            }
            return value;
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
                    if (divisor == 0) {
                        throw new IllegalArgumentException("division by zero");
                    }
                    result /= divisor;
                } else if (c == '%') {
                    pos++;
                    double modulo = parseFactor();
                    if (modulo == 0) {
                        throw new IllegalArgumentException("modulo by zero");
                    }
                    result %= modulo;
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseFactor() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("unexpected end of expression");
            }

            boolean negative = false;
            if (input.charAt(pos) == '-') {
                negative = true;
                pos++;
                skipWhitespace();
            }

            double result;
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++;
                result = parseExpression();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ')') {
                    pos++;
                } else {
                    throw new IllegalArgumentException("missing closing parenthesis");
                }
            } else {
                int start = pos;
                while (pos < input.length()
                        && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                if (start == pos) {
                    throw new IllegalArgumentException("expected number at position " + pos);
                }
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
