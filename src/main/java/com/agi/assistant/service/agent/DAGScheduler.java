package com.agi.assistant.service.agent;

import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import com.agi.assistant.service.rag.HybridRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * DAG-based task scheduler.
 * <p>
 * Executes task nodes following topological ordering with parallel execution
 * of independent nodes using CompletableFuture. Waits for all dependencies
 * to complete before executing a node.
 * <p>
 * 修复说明（本次两处实质性改动）：
 * <ol>
 *   <li><b>依赖结果可见性</b>：此前 {@code results} 这个累积 Map 只在所有节点跑完后
 *       才统一回填，而每个节点的执行体在执行期间去读的正是这个空 Map ——
 *       于是 {@code depResults} 恒为空，上游节点的产出从来传不到下游。
 *       现在节点完成时立即把自己的结果发布进 Map，依赖语义才真正成立。</li>
 *   <li><b>节点处理器落地</b>：此前五个处理器全部只返回
 *       {@code {type: xxx, status: "prepared"}} —— 叫 "prepared" 是因为它们
 *       真的什么都没准备，既不调模型、也不检索、也不调工具。
 *       现在 LLM_CALL 真调模型、RAG_RETRIEVE 真检索、TOOL_CALL 真走工具注册表、
 *       MERGE 真合并、CONDITION 真判断。</li>
 * </ol>
 */
@Slf4j
@Service
public class DAGScheduler {

    private static final int DEFAULT_LLM_MAX_TOKENS = 1500;

    private final Executor executor;
    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final HybridRetrievalService hybridRetrievalService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public DAGScheduler(@Qualifier("dagExecutor") Executor executor,
                        @Lazy WebClient openAiWebClient,
                        OpenAIConfig openAIConfig,
                        @Lazy HybridRetrievalService hybridRetrievalService,
                        @Lazy ToolRegistry toolRegistry) {
        this.executor = executor;
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
        this.hybridRetrievalService = hybridRetrievalService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Schedule and execute all nodes in a DAG.
     * <p>
     * Executes nodes in topological order, running independent nodes in parallel.
     * Each node waits for all its dependencies to complete before execution.
     *
     * @param dag the task DAG to schedule
     * @return a map of node IDs to their execution results
     */
    public Map<String, Object> schedule(TaskDAG dag) {
        if (dag == null || dag.size() == 0) {
            return Collections.emptyMap();
        }

        log.info("Scheduling DAG with {} nodes", dag.size());

        Map<String, CompletableFuture<Object>> futures = new ConcurrentHashMap<>();
        // 共享的结果累积器：节点一完成就写入，供下游节点读取依赖产出
        Map<String, Object> results = new ConcurrentHashMap<>();

        // Get topological order
        List<String> topoOrder = dag.topologicalSort();

        for (String nodeId : topoOrder) {
            TaskDAG.TaskNode node = dag.getNode(nodeId);
            if (node == null) {
                continue;
            }

            // Build future that depends on all predecessors
            List<CompletableFuture<Object>> dependencyFutures = new ArrayList<>();
            for (TaskDAG.TaskNode dep : dag.getDependencies(nodeId)) {
                CompletableFuture<Object> depFuture = futures.get(dep.getId());
                if (depFuture != null) {
                    dependencyFutures.add(depFuture);
                }
            }

            // Create the execution future
            CompletableFuture<Object> nodeFuture;
            if (dependencyFutures.isEmpty()) {
                // No dependencies, can start immediately
                nodeFuture = CompletableFuture.supplyAsync(
                        () -> runNodeAndPublish(dag, nodeId, results), executor);
            } else {
                // Wait for all dependencies
                CompletableFuture<Void> allDeps = CompletableFuture.allOf(
                        dependencyFutures.toArray(new CompletableFuture[0]));
                nodeFuture = allDeps.thenApplyAsync(
                        v -> runNodeAndPublish(dag, nodeId, results), executor);
            }

            futures.put(nodeId, nodeFuture);
        }

        // Wait for all nodes to complete
        try {
            CompletableFuture.allOf(
                    futures.values().toArray(new CompletableFuture[0])
            ).join();
        } catch (Exception e) {
            log.error("DAG scheduling failed: {}", e.getMessage(), e);
        }

        // Collect results（results 已被节点自行写入，这里只补齐失败节点）
        for (Map.Entry<String, CompletableFuture<Object>> entry : futures.entrySet()) {
            try {
                Object result = entry.getValue().join();
                results.put(entry.getKey(), result);
            } catch (Exception e) {
                log.error("Node [{}] execution failed: {}", entry.getKey(), e.getMessage());
                results.putIfAbsent(entry.getKey(), null);
            }
        }

        log.info("DAG scheduling complete: {} nodes executed", results.size());
        return results;
    }

    /**
     * 执行节点并把结果发布到共享 Map，供下游依赖读取。
     * <p>
     * 失败时也要发布一个带 error 的结果 —— 否则下游节点会以为「上游没跑」，
     * 拿到 null 之后静默产出错误结论，比显式失败更难排查。
     */
    private Object runNodeAndPublish(TaskDAG dag, String nodeId, Map<String, Object> sharedResults) {
        Object result = executeNode(dag, nodeId, sharedResults);
        sharedResults.put(nodeId, result);
        return result;
    }

    /**
     * Execute a single node within the DAG context.
     * <p>
     * The node's dependencies' results are available via the results map.
     *
     * @param dag     the task DAG
     * @param nodeId  the node to execute
     * @param results accumulated results from previous nodes
     * @return the execution result of this node
     */
    public Object executeNode(TaskDAG dag, String nodeId, Map<String, Object> results) {
        TaskDAG.TaskNode node = dag.getNode(nodeId);
        if (node == null) {
            log.warn("Node [{}] not found in DAG", nodeId);
            return null;
        }

        log.info("Executing node [{}]: type={}", nodeId, node.getType());
        dag.updateNodeStatus(nodeId, TaskStatus.RUNNING);

        try {
            Object result;

            // Gather dependency results
            Map<String, Object> depResults = new HashMap<>();
            for (TaskDAG.TaskNode dep : dag.getDependencies(nodeId)) {
                Object depResult = results.get(dep.getId());
                depResults.put(dep.getId(), depResult);
            }

            // Execute based on node type
            switch (node.getType()) {
                case LLM_CALL:
                    result = executeLlmCall(node, depResults);
                    break;
                case TOOL_CALL:
                    result = executeToolCall(node, depResults);
                    break;
                case RAG_RETRIEVE:
                    result = executeRagRetrieve(node, depResults);
                    break;
                case CONDITION:
                    result = executeCondition(node, depResults);
                    break;
                case MERGE:
                    result = executeMerge(node, depResults);
                    break;
                default:
                    log.warn("Unknown node type [{}] for node [{}]", node.getType(), nodeId);
                    result = null;
            }

            dag.setNodeResult(nodeId, result);
            dag.updateNodeStatus(nodeId, TaskStatus.COMPLETED);

            log.info("Node [{}] completed successfully", nodeId);
            return result;

        } catch (Exception e) {
            dag.updateNodeStatus(nodeId, TaskStatus.FAILED);
            log.error("Node [{}] execution failed: {}", nodeId, e.getMessage(), e);

            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("type", node.getType() != null ? node.getType().name() : "UNKNOWN");
            failure.put("error", e.getMessage());
            failure.put("status", "failed");
            dag.setNodeResult(nodeId, failure);
            return failure;
        }
    }

    // ----------------------------------------------------------------
    //  Node Type Handlers
    // ----------------------------------------------------------------

    /**
     * LLM_CALL 节点：把上游产出拼成上下文，真实调用一次对话模型。
     * <p>
     * config:
     * <ul>
     *   <li>{@code prompt}  —— 提示词（必填）</li>
     *   <li>{@code model}   —— 模型名，缺省或 "default" 时用 openai.model</li>
     *   <li>{@code maxTokens} —— 最大输出 token</li>
     * </ul>
     */
    private Object executeLlmCall(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String prompt = (String) node.getConfig().getOrDefault("prompt", "");
        String model = (String) node.getConfig().getOrDefault("model", "default");
        if (model == null || model.isBlank() || "default".equalsIgnoreCase(model)) {
            model = openAIConfig.getModel();
        }
        int maxTokens = node.getConfig().containsKey("maxTokens")
                ? ((Number) node.getConfig().get("maxTokens")).intValue()
                : DEFAULT_LLM_MAX_TOKENS;

        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("LLM_CALL node requires a non-blank 'prompt' in config");
        }

        String context = renderDependencyContext(depResults);
        String userContent = context.isBlank() ? prompt : prompt + "\n\n【上游产出】\n" + context;

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", userContent)));
            requestBody.put("temperature", openAIConfig.getTemperature());
            requestBody.put("max_tokens", maxTokens);
            openAIConfig.applyThinking(requestBody);

            String responseStr = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(90))
                    .block();

            String answer = extractMessageContent(responseStr);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "llm_call");
            result.put("model", model);
            result.put("answer", answer);
            result.put("status", "success");
            log.debug("LLM_CALL node completed: model={}, answerLength={}",
                    model, answer == null ? 0 : answer.length());
            return result;

        } catch (Exception e) {
            log.error("LLM_CALL node failed: model={}, err={}", model, e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * TOOL_CALL 节点：真正走一遍工具注册表（含风险分级、阻断与审计）。
     * <p>
     * config: {@code tool}（工具名）、{@code params}（参数 Map）、{@code userId}（可选）
     */
    private Object executeToolCall(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String toolName = (String) node.getConfig().getOrDefault("tool", "");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("TOOL_CALL node requires 'tool' in config");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> configuredParams = (Map<String, Object>) node.getConfig().getOrDefault("params", Map.of());
        Map<String, Object> params = new HashMap<>(configuredParams);

        // 参数里的 ${nodeId} 占位符用上游结果替换，让节点间可以真正串数据
        for (Map.Entry<String, Object> entry : new HashMap<>(params).entrySet()) {
            if (entry.getValue() instanceof String s && s.startsWith("${") && s.endsWith("}")) {
                String refNode = s.substring(2, s.length() - 1);
                Object depValue = depResults.get(refNode);
                params.put(entry.getKey(), depValue == null ? s : depValue);
            }
        }

        Long userId = node.getConfig().containsKey("userId")
                ? ((Number) node.getConfig().get("userId")).longValue()
                : null;

        Map<String, Object> toolResult = toolRegistry.executeTool(toolName, params, userId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "tool_call");
        result.put("tool", toolName);
        result.put("status", toolResult.getOrDefault("status", "UNKNOWN"));
        result.put("result", toolResult.get("result"));
        result.put("error", toolResult.get("error"));
        result.put("raw", toolResult);
        log.debug("TOOL_CALL node completed: tool={}, status={}", toolName, result.get("status"));
        return result;
    }

    /**
     * RAG_RETRIEVE 节点：真实执行混合检索。
     * <p>
     * config: {@code query}（检索词，支持 {@code ${nodeId}} 引用上游）、{@code topK}
     */
    private Object executeRagRetrieve(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        Object rawQuery = node.getConfig().getOrDefault("query", "");
        String query = rawQuery == null ? "" : String.valueOf(rawQuery);

        if (query.startsWith("${") && query.endsWith("}")) {
            String refNode = query.substring(2, query.length() - 1);
            Object depValue = depResults.get(refNode);
            query = extractText(depValue);
        }

        if (query.isBlank()) {
            throw new IllegalArgumentException("RAG_RETRIEVE node resolved to an empty query");
        }

        int topK = node.getConfig().containsKey("topK")
                ? ((Number) node.getConfig().get("topK")).intValue() : 5;
        String strategy = (String) node.getConfig().getOrDefault("strategy", "HYBRID");

        List<SearchResult> chunks = hybridRetrievalService.retrieve(query, strategy, topK);

        List<Map<String, Object>> items = new ArrayList<>();
        StringBuilder preview = new StringBuilder();
        for (SearchResult chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", chunk.getTitle());
            item.put("content", chunk.getContent());
            item.put("source", chunk.getSource());
            item.put("score", chunk.getScore());
            items.add(item);

            String content = chunk.getContent() == null ? "" : chunk.getContent();
            preview.append("- ").append(content, 0, Math.min(content.length(), 300)).append("\n");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "rag_retrieve");
        result.put("query", query);
        result.put("topK", topK);
        result.put("strategy", strategy);
        result.put("count", chunks.size());
        result.put("items", items);
        result.put("preview", preview.toString());
        result.put("status", "success");
        return result;
    }

    /**
     * CONDITION 节点：对上游结果做真实判断，产出 boolean。
     * <p>
     * config:
     * <ul>
     *   <li>{@code ref}  —— 被判断的上游节点 ID</li>
     *   <li>{@code op}   —— 判定方式：nonEmpty / empty / exists / notExists / contains / equals / gt</li>
     *   <li>{@code value} —— contains / equals / gt 的比较值</li>
     * </ul>
     * 缺省 {@code op=nonEmpty}，即「上游有没有产出可用内容」。
     */
    private Object executeCondition(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        Map<String, Object> config = node.getConfig();
        String ref = (String) config.get("ref");
        String op = String.valueOf(config.getOrDefault("op", "nonEmpty"));
        String expected = config.get("value") == null ? null : String.valueOf(config.get("value"));

        Object subject;
        if (ref != null && !ref.isBlank()) {
            subject = depResults.get(ref);
        } else if (depResults.size() == 1) {
            subject = depResults.values().iterator().next();
        } else {
            subject = depResults;
        }

        boolean evaluated = switch (op) {
            case "empty" -> !isMeaningful(subject);
            case "exists" -> subject != null;
            case "notExists" -> subject == null;
            case "contains" -> expected != null && extractText(subject).contains(expected);
            case "equals" -> expected != null && expected.equals(extractText(subject));
            case "gt" -> {
                if (expected == null) {
                    yield false;
                }
                try {
                    yield Double.parseDouble(extractText(subject)) > Double.parseDouble(expected);
                } catch (NumberFormatException nfe) {
                    log.warn("CONDITION gt could not parse numbers: subject={}, expected={}",
                            subject, expected);
                    yield false;
                }
            }
            case "nonEmpty" -> isMeaningful(subject);
            default -> {
                log.warn("Unknown condition op [{}], defaulting to nonEmpty", op);
                yield isMeaningful(subject);
            }
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "condition");
        result.put("op", op);
        result.put("ref", ref);
        result.put("evaluated", evaluated);
        result.put("status", "success");
        log.debug("CONDITION node evaluated: op={}, ref={}, evaluated={}", op, ref, evaluated);
        return result;
    }

    /**
     * MERGE 节点：把上游产出真正合并成一份文本。
     * <p>
     * config: {@code mergeStrategy} —— concat（默认，按分隔拼接）/ first（取第一个非空）/ json（结构化）
     */
    private Object executeMerge(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String strategy = String.valueOf(node.getConfig().getOrDefault("mergeStrategy", "concat"));
        String separator = String.valueOf(node.getConfig().getOrDefault("separator", "\n\n"));

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : depResults.entrySet()) {
            String text = extractText(entry.getValue());
            if (!text.isBlank()) {
                parts.add(text);
            }
        }

        Object merged;
        switch (strategy) {
            case "first" -> merged = parts.isEmpty() ? "" : parts.get(0);
            case "json" -> {
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("type", "merge");
                structured.put("strategy", strategy);
                structured.put("inputs", toJsonSafe(depResults));
                structured.put("text", String.join(separator, parts));
                structured.put("status", "success");
                log.debug("MERGE node completed: strategy={}, parts={}", strategy, parts.size());
                return structured;
            }
            case "concat" -> merged = String.join(separator, parts);
            default -> {
                log.warn("Unknown merge strategy [{}], falling back to concat", strategy);
                merged = String.join(separator, parts);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "merge");
        result.put("strategy", strategy);
        result.put("text", merged);
        result.put("parts", parts.size());
        result.put("status", "success");
        log.debug("MERGE node completed: strategy={}, parts={}", strategy, parts.size());
        return result;
    }

    /**
     * Shutdown the executor service.
     * <p>
     * Note: The executor is now managed by Spring, so this is a no-op.
     * Spring will handle shutdown via ThreadPoolTaskExecutor's
     * waitForTasksToCompleteOnShutdown setting.
     */
    public void shutdown() {
        log.info("DAGScheduler shutdown requested (executor is Spring-managed, no-op)");
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private String renderDependencyContext(Map<String, Object> depResults) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : depResults.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            sb.append("[").append(entry.getKey()).append("] ")
                    .append(extractText(entry.getValue()))
                    .append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 从上游产物里抽出「可读文本」。
     * <p>
     * 上游可能是 RAG 节点的 preview、LLM 节点的 answer、MERGE 节点的 text，
     * 也可能是原始字符串。这里统一成文本，避免下游拿到一坨 Map.toString()。
     */
    @SuppressWarnings("unchecked")
    private String extractText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("answer", "text", "preview", "result")) {
                Object candidate = ((Map<String, Object>) map).get(key);
                if (candidate != null) {
                    return extractText(candidate);
                }
            }
            return map.toString();
        }
        return String.valueOf(value);
    }

    private boolean isMeaningful(Object value) {
        if (value == null) {
            return false;
        }
        String text = extractText(value);
        if (text.isBlank()) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            Object status = ((Map<String, Object>) map).get("status");
            if (status != null && "failed".equalsIgnoreCase(String.valueOf(status))) {
                return false;
            }
            Object count = ((Map<String, Object>) map).get("count");
            if (count instanceof Number number) {
                return number.intValue() > 0;
            }
            Object answered = ((Map<String, Object>) map).get("answer");
            if (answered != null) {
                return !String.valueOf(answered).isBlank();
            }
        }
        return true;
    }

    private Map<String, Object> toJsonSafe(Map<String, Object> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map || value instanceof List || value instanceof Number
                    || value instanceof Boolean || value == null) {
                safe.put(entry.getKey(), value);
            } else {
                safe.put(entry.getKey(), String.valueOf(value));
            }
        }
        return safe;
    }

    @SuppressWarnings("unchecked")
    private String extractMessageContent(String responseStr) {
        if (responseStr == null || responseStr.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return "";
            }
            return String.valueOf(message.get("content"));
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return "";
        }
    }
}
