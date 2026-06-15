package com.agi.assistant.service.memory;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.MemoryType;
import com.agi.assistant.service.rag.EmbeddingService;
import com.agi.assistant.service.rag.MilvusService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Long-term memory service.
 * <p>
 * Stores user preferences and knowledge mastery levels in the database,
 * with embedding-based similarity search for recall. Supports deduplication
 * via content hash and embedding similarity.
 */
@Slf4j
@Lazy
@Service
public class LongTermMemory {

    private static final double SIMILARITY_THRESHOLD = 0.92;

    private final MemoryMapper memoryMapper;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    public LongTermMemory(MemoryMapper memoryMapper,
                          EmbeddingService embeddingService,
                          MilvusService milvusService) {
        this.memoryMapper = memoryMapper;
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Save a memory item for a user.
     * Performs hash-based deduplication before saving.
     *
     * @param userId  the user identifier
     * @param content the memory content text
     * @param type    the memory type (e.g. "preference", "knowledge", "fact")
     * @return the saved memory entity, or null if duplicate detected
     */
    public Memory saveMemory(Long userId, String content, String type) {
        if (userId == null || content == null || content.isBlank()) {
            return null;
        }

        // Hash-based deduplication
        String contentHash = DigestUtils.sha256Hex(content.trim().toLowerCase());
        LambdaQueryWrapper<Memory> hashQuery = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, userId)
                .eq(Memory::getMetadata, buildHashMetadata(contentHash));
        Long existingCount = memoryMapper.selectCount(hashQuery);
        if (existingCount != null && existingCount > 0) {
            log.debug("Duplicate memory detected via hash for user [{}]: hash={}", userId, contentHash);
            return null;
        }

        // Generate embedding for similarity search deduplication
        List<Float> embedding = embeddingService.embed(content);
        if (!embedding.isEmpty()) {
            // Check for embedding-similar memories
            List<SearchResult> similar = milvusService.searchVectors(embedding, 3);
            for (SearchResult result : similar) {
                if (result.getScore() >= SIMILARITY_THRESHOLD) {
                    log.debug("Duplicate memory detected via embedding for user [{}]: score={}",
                            userId, result.getScore());
                    // Update access count on existing memory instead
                    touchSimilarMemory(userId, result.getContent());
                    return null;
                }
            }
        }

        // Persist to database
        Memory memory = new Memory();
        memory.setUserId(userId);
        memory.setContent(content);
        memory.setType(type != null ? type : MemoryType.LONG_TERM.name());
        memory.setImportance(1.0);
        memory.setAccessCount(0);
        memory.setLastAccessedAt(LocalDateTime.now());
        memory.setMetadata(buildSaveMetadata(contentHash));
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());

        memoryMapper.insert(memory);

        // Store embedding in Milvus for future similarity retrieval
        if (!embedding.isEmpty()) {
            String milvusId = "mem_" + memory.getId();
            milvusService.insertVectors(
                    List.of(milvusId),
                    List.of("user_" + userId),
                    List.of(0L),
                    List.of(content),
                    List.of(embedding)
            );
        }

        log.info("Saved long-term memory for user [{}]: type={}, contentLength={}",
                userId, type, content.length());
        return memory;
    }

    /**
     * Recall memories relevant to a query using embedding similarity.
     *
     * @param userId the user identifier
     * @param query  the recall query text
     * @param topK   maximum number of results
     * @return list of relevant memory contents
     */
    public List<String> recallMemory(Long userId, String query, int topK) {
        if (userId == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        List<Float> queryEmbedding = embeddingService.embed(query);
        if (queryEmbedding.isEmpty()) {
            log.warn("Empty embedding for recall query, falling back to keyword search");
            return recallByKeyword(userId, query, topK);
        }

        // Search in Milvus with user filter
        String filterExpr = "document_id == \"user_" + userId + "\"";
        List<SearchResult> results = milvusService.searchVectors(queryEmbedding, topK, filterExpr);

        List<String> recalled = results.stream()
                .map(SearchResult::getContent)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toList());

        // Update access metadata for recalled memories
        for (String content : recalled) {
            touchSimilarMemory(userId, content);
        }

        log.debug("Recalled {} memories for user [{}] via embedding similarity", recalled.size(), userId);
        return recalled;
    }

    /**
     * Get a summary profile of the user based on stored memories.
     *
     * @param userId the user identifier
     * @return a map containing user profile data grouped by memory type
     */
    public Map<String, Object> getUserProfile(Long userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }

        LambdaQueryWrapper<Memory> query = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, userId)
                .orderByDesc(Memory::getImportance)
                .orderByDesc(Memory::getLastAccessedAt);
        List<Memory> memories = memoryMapper.selectList(query);

        Map<String, Object> profile = new HashMap<>();
        Map<String, List<String>> byType = new HashMap<>();

        for (Memory mem : memories) {
            String type = mem.getType() != null ? mem.getType() : "unknown";
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(mem.getContent());
        }

        profile.put("userId", userId);
        profile.put("totalMemories", memories.size());
        profile.put("memoriesByType", byType);

        // Compute average importance
        double avgImportance = memories.stream()
                .mapToDouble(m -> m.getImportance() != null ? m.getImportance() : 0.0)
                .average()
                .orElse(0.0);
        profile.put("averageImportance", avgImportance);

        // Most accessed memories
        List<String> topAccessed = memories.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getAccessCount() != null ? b.getAccessCount() : 0,
                        a.getAccessCount() != null ? a.getAccessCount() : 0))
                .limit(5)
                .map(Memory::getContent)
                .collect(Collectors.toList());
        profile.put("topAccessedMemories", topAccessed);

        log.debug("Built profile for user [{}]: {} memories", userId, memories.size());
        return profile;
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private void touchSimilarMemory(Long userId, String content) {
        try {
            LambdaQueryWrapper<Memory> query = new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, userId)
                    .like(Memory::getContent, content.substring(0, Math.min(content.length(), 100)));
            Memory existing = memoryMapper.selectOne(query);
            if (existing != null) {
                existing.setAccessCount((existing.getAccessCount() != null ? existing.getAccessCount() : 0) + 1);
                existing.setLastAccessedAt(LocalDateTime.now());
                existing.setUpdatedAt(LocalDateTime.now());
                memoryMapper.updateById(existing);
            }
        } catch (Exception e) {
            log.debug("Failed to touch memory: {}", e.getMessage());
        }
    }

    private List<String> recallByKeyword(Long userId, String query, int topK) {
        LambdaQueryWrapper<Memory> queryWrapper = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, userId)
                .like(Memory::getContent, query)
                .orderByDesc(Memory::getImportance)
                .last("LIMIT " + topK);
        List<Memory> memories = memoryMapper.selectList(queryWrapper);
        return memories.stream()
                .map(Memory::getContent)
                .collect(Collectors.toList());
    }

    private String buildHashMetadata(String hash) {
        return "{\"hash\":\"" + hash + "\"}";
    }

    private String buildSaveMetadata(String hash) {
        return "{\"hash\":\"" + hash + "\",\"source\":\"long_term_memory\"}";
    }

}
