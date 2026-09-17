package com.agi.assistant.service.memory;

import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.service.rag.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Context assembly service.
 * <p>
 * Assembles runtime context from all memory layers into a unified prompt.
 * Combines: planner state + tool state + task context + short-term memory
 * + long-term memory + graph memory.
 */
@Slf4j
@Lazy
@Service
public class ContextAssembly {

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final GraphMemory graphMemory;
    private final HybridRetrievalService hybridRetrievalService;
    private final RuntimeStateMemory runtimeStateMemory;

    public ContextAssembly(ShortTermMemory shortTermMemory,
                           LongTermMemory longTermMemory,
                           GraphMemory graphMemory,
                           HybridRetrievalService hybridRetrievalService,
                           RuntimeStateMemory runtimeStateMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.graphMemory = graphMemory;
        this.hybridRetrievalService = hybridRetrievalService;
        this.runtimeStateMemory = runtimeStateMemory;
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Assemble a full context map from all memory layers for a user and task.
     * <p>
     * The returned map contains:
     * - "shortTermMessages": recent conversation messages
     * - "longTermRecall": recalled long-term memories
     * - "graphRelated": related memories from the knowledge graph
     * - "ragResults": retrieved document chunks via hybrid RAG
     * - "userProfile": user profile summary
     *
     * @param userId the user identifier
     * @param taskId the task/query identifier for RAG retrieval
     * @return assembled context map
     */
    public Map<String, Object> assembleContext(Long userId, String taskId) {
        Map<String, Object> context = new HashMap<>();

        // 1. Short-term memory (recent conversation)
        try {
            List<ChatMessage> recentMessages = shortTermMemory.getRecentMessages(
                    userId.toString(), 10);
            context.put("shortTermMessages", recentMessages);
            context.put("shortTermCount", recentMessages.size());
        } catch (Exception e) {
            log.warn("Failed to load short-term memory for user [{}]: {}", userId, e.getMessage());
            context.put("shortTermMessages", List.of());
            context.put("shortTermCount", 0);
        }

        // 2. Long-term memory recall
        try {
            List<String> longTermRecall = longTermMemory.recallMemory(userId, taskId, 5);
            context.put("longTermRecall", longTermRecall);
        } catch (Exception e) {
            log.warn("Failed to recall long-term memory for user [{}]: {}", userId, e.getMessage());
            context.put("longTermRecall", List.of());
        }

        // 3. Graph memory - get memory chain
        try {
            List<Map<String, Object>> memoryChain = graphMemory.getMemoryChain(userId, 10);
            context.put("graphMemoryChain", memoryChain);
        } catch (Exception e) {
            log.warn("Failed to load graph memory for user [{}]: {}", userId, e.getMessage());
            context.put("graphMemoryChain", List.of());
        }

        // 4. RAG retrieval for the task
        try {
            List<SearchResult> ragResults = hybridRetrievalService.retrieve(taskId, 5);
            context.put("ragResults", ragResults);
        } catch (Exception e) {
            log.warn("Failed to retrieve RAG results for task [{}]: {}", taskId, e.getMessage());
            context.put("ragResults", List.of());
        }

        // 5. User profile
        try {
            Map<String, Object> userProfile = longTermMemory.getUserProfile(userId);
            context.put("userProfile", userProfile);
        } catch (Exception e) {
            log.warn("Failed to load user profile for user [{}]: {}", userId, e.getMessage());
            context.put("userProfile", Map.of());
        }

        // 6. Runtime state (Planner + Tool + Task)
        try {
            String sessionId = userId.toString();
            String runtimeContext = runtimeStateMemory.assembleRuntimeContext(sessionId);
            context.put("runtimeState", runtimeContext);
            context.put("plannerState", runtimeStateMemory.getOrCreatePlannerState(sessionId));
            context.put("toolState", runtimeStateMemory.getOrCreateToolState(sessionId));
            context.put("activeTasks", runtimeStateMemory.getActiveTasks(sessionId));
        } catch (Exception e) {
            log.warn("Failed to load runtime state for user [{}]: {}", userId, e.getMessage());
            context.put("runtimeState", "");
        }

        log.debug("Assembled context for user [{}], task [{}]: stm={}, ltm={}, graph={}, rag={}",
                userId, taskId,
                ((List<?>) context.get("shortTermMessages")).size(),
                ((List<?>) context.get("longTermRecall")).size(),
                ((List<?>) context.get("graphMemoryChain")).size(),
                ((List<?>) context.get("ragResults")).size());

        return context;
    }

    /**
     * Build a prompt string enriched with all available context.
     * <p>
     * Constructs a structured prompt that includes:
     * - System instructions
     * - User profile context
     * - Relevant long-term memories
     * - Graph knowledge
     * - Retrieved document context
     * - Recent conversation history
     * - The current user query
     *
     * @param userId  the user identifier
     * @param query   the user's current query
     * @param memories the assembled context map (from assembleContext)
     * @return a formatted prompt string ready for LLM consumption
     */
    @SuppressWarnings("unchecked")
    public String buildPromptWithContext(Long userId, String query, Map<String, Object> memories) {
        StringBuilder prompt = new StringBuilder();

        // System instruction
        prompt.append("你是一个智能学习助手，能够根据用户的记忆和知识库提供个性化帮助。\n\n");

        // 各记忆层拼装（复用与 ChatServiceImpl 相同的逻辑）
        prompt.append(buildMemorySection(memories, true, true));

        // Current query
        prompt.append("## 当前问题\n");
        prompt.append(query).append("\n");

        return prompt.toString();
    }

    /**
     * 只拼装「记忆 + 检索」段落，不含系统人设与当前问题。
     * <p>
     * 抽出来的原因：ChatServiceImpl 有自己的 system prompt 模板（{context}/{memory} 占位符）、
     * 也有自己的一套检索结果，不能再套一份人设和问题；但 runtimeState / userProfile /
     * graphMemoryChain / ragResults 这些上下文此前被 assembleContext 组装出来后无人消费，
     * 等于白查了一遍数据库和向量库。这里给一个「只出段落」的入口，让两边共用同一套渲染。
     *
     * @param memories     assembleContext 的产物
     * @param includeRag   是否渲染 ragResults（调用方若已有参考资料可传 false，避免重复列一遍）
     * @param includeHistory 是否渲染最近对话（调用方若已把历史作为 messages 传入可传 false）
     * @return 已渲染好的段落文本，可能为空串
     */
    @SuppressWarnings("unchecked")
    public String buildMemorySection(Map<String, Object> memories, boolean includeRag, boolean includeHistory) {
        StringBuilder prompt = new StringBuilder();
        if (memories == null || memories.isEmpty()) {
            return "";
        }

        // Runtime state section (Planner + Tool + Task)
        Object runtimeStateRaw = memories.get("runtimeState");
        if (runtimeStateRaw instanceof String runtimeState && !runtimeState.isEmpty()) {
            prompt.append(runtimeState);
        }

        // User profile section
        Object userProfileRaw = memories.get("userProfile");
        if (userProfileRaw instanceof Map<?, ?> rawProfile && !rawProfile.isEmpty()) {
            Map<String, Object> userProfile = (Map<String, Object>) rawProfile;
            prompt.append("## 用户画像\n");
            if (userProfile.get("totalMemories") != null) {
                prompt.append("- 已积累记忆: ").append(userProfile.get("totalMemories")).append(" 条\n");
            }
            Object byTypeRaw = userProfile.get("memoriesByType");
            if (byTypeRaw instanceof Map<?, ?> rawByType) {
                for (Map.Entry<?, ?> entry : rawByType.entrySet()) {
                    if (!(entry.getValue() instanceof List<?> values) || values.isEmpty()) {
                        continue;
                    }
                    List<String> limited = values.stream()
                            .limit(3)
                            .map(String::valueOf)
                            .collect(Collectors.toList());
                    prompt.append("- ").append(entry.getKey()).append(": ");
                    prompt.append(String.join("；", limited));
                    prompt.append("\n");
                }
            }
            prompt.append("\n");
        }

        // Long-term memory recall
        Object recallRaw = memories.get("longTermRecall");
        if (recallRaw instanceof List<?> rawRecall && !rawRecall.isEmpty()) {
            prompt.append("## 相关记忆\n");
            for (int i = 0; i < rawRecall.size(); i++) {
                prompt.append(i + 1).append(". ").append(rawRecall.get(i)).append("\n");
            }
            prompt.append("\n");
        }

        // Graph memory chain
        Object graphRaw = memories.get("graphMemoryChain");
        if (graphRaw instanceof List<?> rawGraph && !rawGraph.isEmpty()) {
            prompt.append("## 知识图谱关联\n");
            for (Object item : rawGraph) {
                if (!(item instanceof Map<?, ?> node)) {
                    continue;
                }
                prompt.append("- ").append(node.get("content"));
                if (node.get("importance") != null) {
                    prompt.append(" [重要性: ").append(node.get("importance")).append("]");
                }
                prompt.append("\n");
            }
            prompt.append("\n");
        }

        // RAG retrieved context
        if (includeRag) {
            Object ragRaw = memories.get("ragResults");
            if (ragRaw instanceof List<?> rawRag && !rawRag.isEmpty()) {
                prompt.append("## 参考资料\n");
                int idx = 0;
                for (Object item : rawRag) {
                    if (!(item instanceof SearchResult result)) {
                        continue;
                    }
                    idx++;
                    prompt.append("[").append(idx).append("] ");
                    if (result.getTitle() != null) {
                        prompt.append(result.getTitle()).append(": ");
                    }
                    prompt.append(result.getContent());
                    if (result.getSource() != null) {
                        prompt.append(" (来源: ").append(result.getSource()).append(")");
                    }
                    prompt.append("\n");
                }
                if (idx > 0) {
                    prompt.append("\n");
                }
            }
        }

        // Recent conversation
        if (includeHistory) {
            Object historyRaw = memories.get("shortTermMessages");
            if (historyRaw instanceof List<?> rawHistory && !rawHistory.isEmpty()) {
                prompt.append("## 最近对话\n");
                for (Object item : rawHistory) {
                    if (!(item instanceof ChatMessage msg)) {
                        continue;
                    }
                    prompt.append("[").append(msg.getRole()).append("] ");
                    prompt.append(msg.getContent()).append("\n");
                }
                prompt.append("\n");
            }
        }

        return prompt.toString();
    }
}
