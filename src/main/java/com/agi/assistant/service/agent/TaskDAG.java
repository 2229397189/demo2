package com.agi.assistant.service.agent;

import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Directed Acyclic Graph (DAG) for task scheduling.
 * <p>
 * Manages task nodes and their dependency edges. Provides topological sorting,
 * root discovery, and dependency/dependent lookup.
 */
@Slf4j
@Component
public class TaskDAG {

    private final Map<String, TaskNode> nodes = new HashMap<>();
    private final Map<String, List<TaskEdge>> adjacencyList = new HashMap<>();
    private final Map<String, List<TaskEdge>> reverseAdjacency = new HashMap<>();

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Add a task node to the DAG.
     *
     * @param node the task node to add
     * @throws IllegalArgumentException if a node with the same ID already exists
     */
    public void addNode(TaskNode node) {
        if (node == null || node.getId() == null) {
            throw new IllegalArgumentException("Node and node ID must not be null");
        }
        if (nodes.containsKey(node.getId())) {
            throw new IllegalArgumentException("Node already exists: " + node.getId());
        }

        nodes.put(node.getId(), node);
        adjacencyList.put(node.getId(), new ArrayList<>());
        reverseAdjacency.put(node.getId(), new ArrayList<>());

        log.debug("Added task node: id={}, type={}", node.getId(), node.getType());
    }

    /**
     * Add a dependency edge from source to target.
     * The target depends on the source (source must complete before target starts).
     *
     * @param sourceId the source node ID
     * @param targetId the target node ID
     * @throws IllegalArgumentException if either node does not exist or edge creates a cycle
     */
    public void addEdge(String sourceId, String targetId) {
        if (!nodes.containsKey(sourceId)) {
            throw new IllegalArgumentException("Source node not found: " + sourceId);
        }
        if (!nodes.containsKey(targetId)) {
            throw new IllegalArgumentException("Target node not found: " + targetId);
        }

        // Check for self-loop
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Self-loop detected: " + sourceId);
        }

        TaskEdge edge = new TaskEdge(sourceId, targetId);
        adjacencyList.get(sourceId).add(edge);
        reverseAdjacency.get(targetId).add(edge);

        // Validate no cycle is introduced
        if (hasCycle()) {
            // Rollback
            adjacencyList.get(sourceId).remove(edge);
            reverseAdjacency.get(targetId).remove(edge);
            throw new IllegalArgumentException(
                    "Adding edge " + sourceId + " -> " + targetId + " would create a cycle");
        }

        log.debug("Added edge: {} -> {}", sourceId, targetId);
    }

    /**
     * Get all root nodes (nodes with no incoming edges).
     *
     * @return list of root nodes
     */
    public List<TaskNode> getRoots() {
        List<TaskNode> roots = new ArrayList<>();
        for (Map.Entry<String, TaskNode> entry : nodes.entrySet()) {
            if (reverseAdjacency.getOrDefault(entry.getKey(), List.of()).isEmpty()) {
                roots.add(entry.getValue());
            }
        }
        return roots;
    }

    /**
     * Get all dependencies (predecessors) of a node.
     *
     * @param nodeId the node ID
     * @return list of nodes that must complete before this node
     */
    public List<TaskNode> getDependencies(String nodeId) {
        List<TaskNode> dependencies = new ArrayList<>();
        for (TaskEdge edge : reverseAdjacency.getOrDefault(nodeId, List.of())) {
            TaskNode dep = nodes.get(edge.getSource());
            if (dep != null) {
                dependencies.add(dep);
            }
        }
        return dependencies;
    }

    /**
     * Get all dependents (successors) of a node.
     *
     * @param nodeId the node ID
     * @return list of nodes that depend on this node
     */
    public List<TaskNode> getDependents(String nodeId) {
        List<TaskNode> dependents = new ArrayList<>();
        for (TaskEdge edge : adjacencyList.getOrDefault(nodeId, List.of())) {
            TaskNode dep = nodes.get(edge.getTarget());
            if (dep != null) {
                dependents.add(dep);
            }
        }
        return dependents;
    }

    /**
     * Perform topological sort of the DAG.
     * Uses Kahn's algorithm (BFS-based).
     *
     * @return list of node IDs in topological order
     * @throws IllegalStateException if the graph contains a cycle
     */
    public List<String> topologicalSort() {
        // Compute in-degrees
        Map<String, Integer> inDegree = new HashMap<>();
        for (String nodeId : nodes.keySet()) {
            inDegree.put(nodeId, 0);
        }
        for (List<TaskEdge> edges : adjacencyList.values()) {
            for (TaskEdge edge : edges) {
                inDegree.merge(edge.getTarget(), 1, Integer::sum);
            }
        }

        // BFS from zero-in-degree nodes
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);

            for (TaskEdge edge : adjacencyList.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(edge.getTarget(), -1, Integer::sum);
                if (newDegree == 0) {
                    queue.add(edge.getTarget());
                }
            }
        }

        if (sorted.size() != nodes.size()) {
            throw new IllegalStateException("Cycle detected in DAG: sorted " +
                    sorted.size() + " of " + nodes.size() + " nodes");
        }

        log.debug("Topological sort produced {} nodes", sorted.size());
        return sorted;
    }

    /**
     * Get a node by ID.
     *
     * @param nodeId the node ID
     * @return the task node, or null if not found
     */
    public TaskNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * Get all nodes in the DAG.
     *
     * @return unmodifiable collection of all nodes
     */
    public Map<String, TaskNode> getAllNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Update the status of a node.
     *
     * @param nodeId the node ID
     * @param status the new status
     */
    public void updateNodeStatus(String nodeId, TaskStatus status) {
        TaskNode node = nodes.get(nodeId);
        if (node != null) {
            node.setStatus(status);
            log.debug("Updated node [{}] status to {}", nodeId, status);
        }
    }

    /**
     * Set the result of a completed node.
     *
     * @param nodeId the node ID
     * @param result the execution result
     */
    public void setNodeResult(String nodeId, Object result) {
        TaskNode node = nodes.get(nodeId);
        if (node != null) {
            node.setResult(result);
        }
    }

    /**
     * Clear all nodes and edges.
     */
    public void clear() {
        nodes.clear();
        adjacencyList.clear();
        reverseAdjacency.clear();
        log.debug("DAG cleared");
    }

    /**
     * Get the number of nodes in the DAG.
     */
    public int size() {
        return nodes.size();
    }

    // ----------------------------------------------------------------
    //  Cycle Detection
    // ----------------------------------------------------------------

    private boolean hasCycle() {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String nodeId : nodes.keySet()) {
            if (detectCycleDFS(nodeId, visited, inStack)) {
                return true;
            }
        }
        return false;
    }

    private boolean detectCycleDFS(String nodeId, Set<String> visited, Set<String> inStack) {
        if (inStack.contains(nodeId)) {
            return true;
        }
        if (visited.contains(nodeId)) {
            return false;
        }

        visited.add(nodeId);
        inStack.add(nodeId);

        for (TaskEdge edge : adjacencyList.getOrDefault(nodeId, List.of())) {
            if (detectCycleDFS(edge.getTarget(), visited, inStack)) {
                return true;
            }
        }

        inStack.remove(nodeId);
        return false;
    }

    // ----------------------------------------------------------------
    //  Inner Classes
    // ----------------------------------------------------------------

    /**
     * A node in the task DAG representing a single unit of work.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskNode {

        /**
         * Unique node identifier.
         */
        private String id;

        /**
         * The type of operation this node performs.
         */
        private NodeType type;

        /**
         * Configuration parameters for this node.
         */
        @Builder.Default
        private Map<String, Object> config = new HashMap<>();

        /**
         * Current execution status.
         */
        @Builder.Default
        private TaskStatus status = TaskStatus.INITIALIZED;

        /**
         * The result produced by this node after execution.
         */
        private Object result;
    }

    /**
     * A directed edge in the task DAG representing a dependency.
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TaskEdge {

        /**
         * The source node ID (the dependency).
         */
        private String source;

        /**
         * The target node ID (the dependent).
         */
        private String target;
    }
}
