package com.agi.assistant.service.agent;

import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAG-based task scheduler.
 * <p>
 * Executes task nodes following topological ordering with parallel execution
 * of independent nodes using CompletableFuture. Waits for all dependencies
 * to complete before executing a node.
 */
@Slf4j
@Service
public class DAGScheduler {

    private final ExecutorService executor;

    public DAGScheduler() {
        this.executor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors());
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
                        () -> executeNode(dag, nodeId, results), executor);
            } else {
                // Wait for all dependencies
                CompletableFuture<Void> allDeps = CompletableFuture.allOf(
                        dependencyFutures.toArray(new CompletableFuture[0]));
                nodeFuture = allDeps.thenApplyAsync(
                        v -> executeNode(dag, nodeId, results), executor);
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

        // Collect results
        for (Map.Entry<String, CompletableFuture<Object>> entry : futures.entrySet()) {
            try {
                Object result = entry.getValue().join();
                results.put(entry.getKey(), result);
            } catch (Exception e) {
                log.error("Node [{}] execution failed: {}", entry.getKey(), e.getMessage());
                results.put(entry.getKey(), null);
            }
        }

        log.info("DAG scheduling complete: {} nodes executed", results.size());
        return results;
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
            throw new RuntimeException("Node execution failed: " + nodeId, e);
        }
    }

    // ----------------------------------------------------------------
    //  Node Type Handlers
    // ----------------------------------------------------------------

    /**
     * Execute an LLM call node.
     * Delegates to the configured prompt/model in the node config.
     */
    private Object executeLlmCall(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String prompt = (String) node.getConfig().getOrDefault("prompt", "");
        String model = (String) node.getConfig().getOrDefault("model", "default");

        // Merge dependency outputs into the prompt context
        StringBuilder contextBuilder = new StringBuilder();
        for (Map.Entry<String, Object> entry : depResults.entrySet()) {
            if (entry.getValue() != null) {
                contextBuilder.append("[").append(entry.getKey()).append("] ");
                contextBuilder.append(entry.getValue().toString()).append("\n");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", "llm_call");
        result.put("prompt", prompt);
        result.put("model", model);
        result.put("context", contextBuilder.toString());
        result.put("status", "prepared");

        log.debug("LLM call node prepared: model={}", model);
        return result;
    }

    /**
     * Execute a tool call node.
     */
    private Object executeToolCall(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String toolName = (String) node.getConfig().getOrDefault("tool", "unknown");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) node.getConfig().getOrDefault("params", Map.of());

        Map<String, Object> result = new HashMap<>();
        result.put("type", "tool_call");
        result.put("tool", toolName);
        result.put("params", params);
        result.put("status", "prepared");

        log.debug("Tool call node prepared: tool={}", toolName);
        return result;
    }

    /**
     * Execute a RAG retrieval node.
     */
    private Object executeRagRetrieve(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String query = (String) node.getConfig().getOrDefault("query", "");
        int topK = node.getConfig().containsKey("topK")
                ? ((Number) node.getConfig().get("topK")).intValue() : 5;

        Map<String, Object> result = new HashMap<>();
        result.put("type", "rag_retrieve");
        result.put("query", query);
        result.put("topK", topK);
        result.put("status", "prepared");

        log.debug("RAG retrieve node prepared: query='{}', topK={}", query, topK);
        return result;
    }

    /**
     * Execute a condition/branch node.
     */
    private Object executeCondition(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String condition = (String) node.getConfig().getOrDefault("condition", "true");

        Map<String, Object> result = new HashMap<>();
        result.put("type", "condition");
        result.put("condition", condition);
        result.put("evaluated", true);
        result.put("status", "prepared");

        log.debug("Condition node prepared: condition='{}'", condition);
        return result;
    }

    /**
     * Execute a merge node that aggregates results from multiple predecessors.
     */
    private Object executeMerge(TaskDAG.TaskNode node, Map<String, Object> depResults) {
        String strategy = (String) node.getConfig().getOrDefault("mergeStrategy", "concat");

        Map<String, Object> result = new HashMap<>();
        result.put("type", "merge");
        result.put("strategy", strategy);
        result.put("inputs", depResults);
        result.put("status", "prepared");

        log.debug("Merge node prepared: strategy={}, inputs={}", strategy, depResults.size());
        return result;
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        executor.shutdown();
        log.info("DAGScheduler executor shut down");
    }
}
