package com.agi.assistant.service.memory;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.enums.MemoryType;
import com.agi.assistant.service.rag.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Graph-aware memory consolidation service.
 * <p>
 * Consolidates short-term conversation memories into long-term storage by:
 * 1. Extracting facts from conversations via LLM
 * 2. Deduplicating via hash and embedding similarity
 * 3. Applying TTL expiration and importance decay
 * 4. Writing consolidated memories to the graph
 */
@Slf4j
@Service
public class MemoryConsolidation {

    private static final String FACT_EXTRACTION_PROMPT =
            "请从以下对话中提取用户明确表达的偏好、习惯、知识和事实。\n\n" +
            "【重要规则】\n" +
            "1. 只提取用户（[user] 标签）明确说出的信息\n" +
            "2. 不要提取 AI（[assistant] 标签）回复中的推断、建议或引用的内容\n" +
            "3. 不要提取简历、文档中的信息，除非用户明确说「我的简历写了...」\n" +
            "4. 区分「用户说的」和「AI说的」，只记录用户主动表达的内容\n\n" +
            "以 JSON 数组格式输出，每个元素包含：\n" +
            "- \"content\": 事实内容（必须是用户原话或明确表达的意思）\n" +
            "- \"type\": 类型（preference / knowledge / fact / habit）\n" +
            "- \"importance\": 重要性（0.0-1.0）\n" +
            "- \"source\": 来源（user/ai，标记信息来源）\n\n" +
            "对话内容：\n";

    private static final double SIMILARITY_DEDUP_THRESHOLD = 0.90;
    private static final double DECAY_FACTOR = 0.95;
    private static final double MIN_IMPORTANCE = 0.1;
    private static final int DEFAULT_IMPORTANCE_DECAY_DAYS = 7;

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final GraphMemory graphMemory;
    private final EmbeddingService embeddingService;
    private final MemoryMapper memoryMapper;
    private final WebClient openAiWebClient;
    private final ObjectMapper objectMapper;

    public MemoryConsolidation(ShortTermMemory shortTermMemory,
                               LongTermMemory longTermMemory,
                               GraphMemory graphMemory,
                               EmbeddingService embeddingService,
                               MemoryMapper memoryMapper,
                               WebClient openAiWebClient) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.graphMemory = graphMemory;
        this.embeddingService = embeddingService;
        this.memoryMapper = memoryMapper;
        this.openAiWebClient = openAiWebClient;
        this.objectMapper = new ObjectMapper();
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Run the full consolidation pipeline for a user.
     * <p>
     * Steps:
     * 1. Retrieve recent short-term memories
     * 2. Extract facts from conversation via LLM
     * 3. Deduplicate extracted facts
     * 4. Save to long-term memory and graph
     * 5. Decay importance of older memories
     *
     * @param userId the user identifier
     */
    public void consolidate(Long userId) {
        if (userId == null) {
            return;
        }

        log.info("Starting memory consolidation for user [{}]", userId);

        try {
            // 1. Get recent short-term messages
            List<ChatMessage> recentMessages = shortTermMemory.getRecentMessages(
                    userId.toString(), 20);
            if (recentMessages.isEmpty()) {
                log.debug("No recent messages for user [{}], skipping consolidation", userId);
                return;
            }

            // 2. Extract facts from conversation
            String conversation = formatConversation(recentMessages);
            List<Map<String, Object>> extractedFacts = extractFacts(conversation);
            if (extractedFacts.isEmpty()) {
                log.debug("No facts extracted for user [{}]", userId);
                return;
            }

            // 3. Deduplicate
            List<Map<String, Object>> deduplicated = deduplicate(userId, extractedFacts);

            // 4. Save to long-term memory and graph
            int saved = 0;
            for (Map<String, Object> fact : deduplicated) {
                String content = (String) fact.get("content");
                String type = (String) fact.getOrDefault("type", "fact");
                double importance = fact.containsKey("importance")
                        ? ((Number) fact.get("importance")).doubleValue() : 0.5;

                Memory memory = longTermMemory.saveMemory(userId, content, type);
                if (memory != null) {
                    // Add to graph memory
                    String memoryId = "mem_" + memory.getId();
                    graphMemory.addMemoryNode(userId, memoryId, content, importance);

                    // Link to topics if available
                    if (fact.containsKey("topic")) {
                        graphMemory.linkMemoryToTopic(memoryId, (String) fact.get("topic"));
                    }

                    saved++;
                }
            }

            // 5. Decay importance of old memories
            decayImportance(userId);

            log.info("Consolidation complete for user [{}]: extracted={}, deduplicated={}, saved={}",
                    userId, extractedFacts.size(), deduplicated.size(), saved);

        } catch (Exception e) {
            log.error("Memory consolidation failed for user [{}]: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * Extract facts from a conversation using LLM.
     *
     * @param conversation the formatted conversation text
     * @return list of extracted facts, each containing content, type, and importance
     */
    public List<Map<String, Object>> extractFacts(String conversation) {
        if (conversation == null || conversation.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String prompt = FACT_EXTRACTION_PROMPT + conversation;

            Map<String, Object> requestBody = Map.of(
                    "model", "qwen-turbo",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.2,
                    "max_tokens", 3000
            );

            String responseStr = openAiWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null) {
                log.warn("LLM returned null response for fact extraction");
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            String jsonStr = extractJsonArray(content);
            if (jsonStr == null) {
                log.warn("Failed to extract JSON array from LLM response");
                return Collections.emptyList();
            }

            List<Map<String, Object>> facts = objectMapper.readValue(jsonStr,
                    new TypeReference<List<Map<String, Object>>>() {});

            // 过滤掉 AI 来源的事实，只保留用户明确表达的内容
            List<Map<String, Object>> userFacts = facts.stream()
                    .filter(fact -> {
                        String source = (String) fact.getOrDefault("source", "user");
                        return "user".equalsIgnoreCase(source);
                    })
                    .collect(Collectors.toList());

            log.debug("Extracted {} facts from conversation, {} from user",
                    facts.size(), userFacts.size());
            return userFacts;

        } catch (Exception e) {
            log.error("Fact extraction failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Deduplicate a list of facts against existing memories.
     * Uses both hash-based and embedding similarity deduplication.
     *
     * @param userId the user identifier
     * @param facts  the list of facts to deduplicate
     * @return the deduplicated list
     */
    public List<Map<String, Object>> deduplicate(Long userId, List<Map<String, Object>> facts) {
        if (facts == null || facts.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> seenHashes = new HashSet<>();
        List<Map<String, Object>> deduplicated = new ArrayList<>();

        for (Map<String, Object> fact : facts) {
            String content = (String) fact.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }

            // Hash-based dedup
            String hash = DigestUtils.sha256Hex(content.trim().toLowerCase());
            if (seenHashes.contains(hash)) {
                log.debug("Dedup: duplicate hash within batch: {}", hash.substring(0, 12));
                continue;
            }
            seenHashes.add(hash);

            // Embedding similarity dedup against existing memories
            List<Float> embedding = embeddingService.embed(content);
            if (!embedding.isEmpty()) {
                List<String> existing = longTermMemory.recallMemory(userId, content, 3);
                boolean isDuplicate = false;

                for (String existingContent : existing) {
                    List<Float> existingEmbedding = embeddingService.embed(existingContent);
                    if (!existingEmbedding.isEmpty()) {
                        double similarity = cosineSimilarity(embedding, existingEmbedding);
                        if (similarity >= SIMILARITY_DEDUP_THRESHOLD) {
                            log.debug("Dedup: similarity {} exceeds threshold for: {}",
                                    similarity, content.substring(0, Math.min(content.length(), 50)));
                            isDuplicate = true;
                            break;
                        }
                    }
                }

                if (isDuplicate) {
                    continue;
                }
            }

            deduplicated.add(fact);
        }

        log.debug("Deduplication: {} -> {} facts", facts.size(), deduplicated.size());
        return deduplicated;
    }

    /**
     * Apply importance decay to older memories.
     * Memories that haven't been accessed recently lose importance over time.
     * Memories below the minimum importance threshold are candidates for removal.
     */
    public void decayImportance() {
        try {
            List<Memory> allMemories = memoryMapper.selectList(null);
            int decayed = 0;

            for (Memory memory : allMemories) {
                if (memory.getLastAccessedAt() == null) {
                    continue;
                }

                long daysSinceAccess = ChronoUnit.DAYS.between(
                        memory.getLastAccessedAt(), LocalDateTime.now());

                if (daysSinceAccess >= DEFAULT_IMPORTANCE_DECAY_DAYS) {
                    double currentImportance = memory.getImportance() != null
                            ? memory.getImportance() : 1.0;
                    double newImportance = currentImportance * DECAY_FACTOR;

                    if (newImportance < MIN_IMPORTANCE) {
                        newImportance = MIN_IMPORTANCE;
                    }

                    if (Math.abs(newImportance - currentImportance) > 0.001) {
                        memory.setImportance(newImportance);
                        memory.setUpdatedAt(LocalDateTime.now());
                        memoryMapper.updateById(memory);
                        decayed++;
                    }
                }
            }

            log.info("Importance decay applied: {} memories updated", decayed);

        } catch (Exception e) {
            log.error("Importance decay failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Apply importance decay for a specific user's memories.
     *
     * @param userId the user identifier
     */
    public void decayImportance(Long userId) {
        if (userId == null) {
            return;
        }

        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Memory> query =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Memory>()
                            .eq(Memory::getUserId, userId);
            List<Memory> userMemories = memoryMapper.selectList(query);
            int decayed = 0;

            for (Memory memory : userMemories) {
                if (memory.getLastAccessedAt() == null) {
                    continue;
                }

                long daysSinceAccess = ChronoUnit.DAYS.between(
                        memory.getLastAccessedAt(), LocalDateTime.now());

                if (daysSinceAccess >= DEFAULT_IMPORTANCE_DECAY_DAYS) {
                    double currentImportance = memory.getImportance() != null
                            ? memory.getImportance() : 1.0;
                    double newImportance = currentImportance * DECAY_FACTOR;

                    if (newImportance < MIN_IMPORTANCE) {
                        newImportance = MIN_IMPORTANCE;
                    }

                    if (Math.abs(newImportance - currentImportance) > 0.001) {
                        memory.setImportance(newImportance);
                        memory.setUpdatedAt(LocalDateTime.now());
                        memoryMapper.updateById(memory);
                        decayed++;
                    }
                }
            }

            log.debug("Importance decay for user [{}]: {} memories updated", userId, decayed);

        } catch (Exception e) {
            log.error("Importance decay for user [{}] failed: {}", userId, e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private String formatConversation(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append("[").append(msg.getRole()).append("] ");
            sb.append(msg.getContent());
            sb.append("\n");
        }
        return sb.toString();
    }

    private String extractJsonArray(String content) {
        if (content == null) {
            return null;
        }

        // Try ```json ... ``` block
        int start = content.indexOf("```json");
        if (start >= 0) {
            int end = content.indexOf("```", start + 7);
            if (end > start) {
                return content.substring(start + 7, end).strip();
            }
        }

        // Try [ ... ] block
        int bracketStart = content.indexOf('[');
        int bracketEnd = content.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return content.substring(bracketStart, bracketEnd + 1);
        }

        return null;
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            float va = a.get(i);
            float vb = b.get(i);
            dotProduct += va * vb;
            normA += va * va;
            normB += vb * vb;
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dotProduct / denominator;
    }
}
