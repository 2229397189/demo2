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

    public ContextAssembly(ShortTermMemory shortTermMemory,
                           LongTermMemory longTermMemory,
                           GraphMemory graphMemory,
                           HybridRetrievalService hybridRetrievalService) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.graphMemory = graphMemory;
        this.hybridRetrievalService = hybridRetrievalService;
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

        // User profile section
        Map<String, Object> userProfile = (Map<String, Object>) memories.getOrDefault("userProfile", Map.of());
        if (!userProfile.isEmpty()) {
            prompt.append("## 用户画像\n");
            if (userProfile.containsKey("totalMemories")) {
                prompt.append("- 已积累记忆: ").append(userProfile.get("totalMemories")).append(" 条\n");
            }
            Map<String, List<String>> byType = (Map<String, List<String>>) userProfile.getOrDefault("memoriesByType", Map.of());
            for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
                prompt.append("- ").append(entry.getKey()).append(": ");
                prompt.append(String.join("；", entry.getValue().stream()
                        .limit(3).collect(Collectors.toList())));
                prompt.append("\n");
            }
            prompt.append("\n");
        }

        // Long-term memory recall
        List<String> longTermRecall = (List<String>) memories.getOrDefault("longTermRecall", List.of());
        if (!longTermRecall.isEmpty()) {
            prompt.append("## 相关记忆\n");
            for (int i = 0; i < longTermRecall.size(); i++) {
                prompt.append(i + 1).append(". ").append(longTermRecall.get(i)).append("\n");
            }
            prompt.append("\n");
        }

        // Graph memory chain
        List<Map<String, Object>> graphChain = (List<Map<String, Object>>) memories.getOrDefault("graphMemoryChain", List.of());
        if (!graphChain.isEmpty()) {
            prompt.append("## 知识图谱关联\n");
            for (Map<String, Object> item : graphChain) {
                prompt.append("- ").append(item.get("content"));
                if (item.containsKey("importance")) {
                    prompt.append(" [重要性: ").append(item.get("importance")).append("]");
                }
                prompt.append("\n");
            }
            prompt.append("\n");
        }

        // RAG retrieved context
        List<SearchResult> ragResults = (List<SearchResult>) memories.getOrDefault("ragResults", List.of());
        if (!ragResults.isEmpty()) {
            prompt.append("## 参考资料\n");
            for (int i = 0; i < ragResults.size(); i++) {
                SearchResult result = ragResults.get(i);
                prompt.append("[").append(i + 1).append("] ");
                if (result.getTitle() != null) {
                    prompt.append(result.getTitle()).append(": ");
                }
                prompt.append(result.getContent());
                prompt.append(" (来源: ").append(result.getSource()).append(")\n");
            }
            prompt.append("\n");
        }

        // Recent conversation
        List<ChatMessage> recentMessages = (List<ChatMessage>) memories.getOrDefault("shortTermMessages", List.of());
        if (!recentMessages.isEmpty()) {
            prompt.append("## 最近对话\n");
            for (ChatMessage msg : recentMessages) {
                prompt.append("[").append(msg.getRole()).append("] ");
                prompt.append(msg.getContent()).append("\n");
            }
            prompt.append("\n");
        }

        // Current query
        prompt.append("## 当前问题\n");
        prompt.append(query).append("\n");

        return prompt.toString();
    }
}
