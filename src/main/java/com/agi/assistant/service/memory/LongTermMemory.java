package com.agi.assistant.service.memory;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.MemoryCategory;
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
     * @param type    语义类别（会被 {@link MemoryCategory#normalize(String)} 归一为
     *                FACT/PREFERENCE/KNOWLEDGE/HABIT/SUMMARY 之一，大小写不敏感）
     * @return the saved memory entity, or null if duplicate detected
     */
    public Memory saveMemory(Long userId, String content, String type) {
        return saveMemory(userId, content, type, 1.0, null);
    }

    /**
     * Save a memory item with an explicit importance score and TTL.
     *
     * @param userId     the user identifier
     * @param content    the memory content text
     * @param type       the memory type
     * @param importance importance score (0.0 - 1.0)
     * @param expiresAt  expiry time, or null for no expiry
     * @return the saved memory entity, or null if duplicate detected
     */
    public Memory saveMemory(Long userId, String content, String type,
                             Double importance, LocalDateTime expiresAt) {
        if (userId == null || content == null || content.isBlank()) {
            return null;
        }

        // 哈希去重。
        // 注意：metadata 里除了 hash 还存了 source 字段，所以必须按 JSON 路径取值比较，
        // 不能用「整串 metadata 相等」——那永远不会命中（历史 bug，去重形同虚设）。
        String contentHash = DigestUtils.sha256Hex(content.trim().toLowerCase());
        LambdaQueryWrapper<Memory> hashQuery = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, userId)
                .apply("JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.hash')) = {0}", contentHash);
        Long existingCount = memoryMapper.selectCount(hashQuery);
        if (existingCount != null && existingCount > 0) {
            log.debug("Duplicate memory detected via hash for user [{}]: hash={}", userId, contentHash);
            return null;
        }

        // Generate embedding for similarity search deduplication
        List<Float> embedding = embeddingService.embed(content);
        if (!embedding.isEmpty()) {
            // Check for embedding-similar memories.
            // 必须带 user 过滤（P2-6）：document_id 里存的就是 "user_{id}"，
            // 不加过滤会把「别人的相似记忆」当成重复，既误判又跨用户泄露内容相似性。
            String filterExpr = "document_id == \"user_" + userId + "\"";
            List<SearchResult> similar = milvusService.searchVectors(embedding, 3, filterExpr);
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
        // memory.type 存的是「语义类别」，统一归一为大写规范 token（FACT/PREFERENCE/...）。
        // 历史小写 / 未知取值经 normalize 收敛，保证写入口径唯一。
        memory.setType(MemoryCategory.normalize(type));
        memory.setImportance(importance != null ? importance : 1.0);
        memory.setAccessCount(0);
        memory.setLastAccessedAt(LocalDateTime.now());
        memory.setMetadata(buildSaveMetadata(contentHash));
        memory.setExpiresAt(expiresAt);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setUpdatedAt(LocalDateTime.now());

        memoryMapper.insert(memory);

        // Store embedding in Milvus for future similarity retrieval
        if (!embedding.isEmpty()) {
            String milvusId = "mem_" + memory.getId();
            memory.setEmbeddingId(milvusId);
            memoryMapper.updateById(memory);
            milvusService.insertVectors(
                    List.of(milvusId),
                    List.of("user_" + userId),
                    List.of(0L),
                    List.of(content),
                    List.of(embedding)
            );
        }

        log.info("Saved long-term memory for user [{}]: type={}, importance={}, contentLength={}",
                userId, type, memory.getImportance(), content.length());
        return memory;
    }

    /**
     * 找出与给定内容语义相近的既有记忆（用于建立 SIMILAR_TO 图边）。
     * <p>
     * 先走向量召回拿到相似内容，再按内容精确回查数据库拿到记忆 ID
     * （向量库只存内容，不存业务主键）。
     *
     * @param userId    用户 ID
     * @param content   目标内容
     * @param threshold 相似度阈值
     * @param topK      最多返回条数
     * @return 相近的记忆及其相似度列表
     */
    public List<SimilarMemory> findSimilar(Long userId, String content, double threshold, int topK) {
        if (userId == null || content == null || content.isBlank()) {
            return Collections.emptyList();
        }

        List<Float> embedding = embeddingService.embed(content);
        if (embedding.isEmpty()) {
            return Collections.emptyList();
        }

        String filterExpr = "document_id == \"user_" + userId + "\"";
        List<SearchResult> hits = milvusService.searchVectors(embedding, Math.max(1, topK), filterExpr);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }

        List<SimilarMemory> result = new ArrayList<>();
        for (SearchResult hit : hits) {
            if (hit.getScore() < threshold) {
                continue;
            }
            String hitContent = hit.getContent();
            if (hitContent == null || hitContent.isBlank() || hitContent.equals(content)) {
                continue;
            }
            Memory matched = memoryMapper.selectOne(new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, userId)
                    .eq(Memory::getContent, hitContent)
                    .last("LIMIT 1"));
            if (matched != null) {
                result.add(new SimilarMemory(matched, hit.getScore()));
            }
        }
        return result;
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
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            // 用内容精确匹配 + LIMIT 1。
            // 旧实现用 like(前 100 字符) 且没有 LIMIT，多条命中时 selectOne 会抛
            // TooManyResultsException 被 catch 吞掉，等于「访问计数永不更新」。
            LambdaQueryWrapper<Memory> query = new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, userId)
                    .eq(Memory::getContent, content)
                    .last("LIMIT 1");
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

    private String buildSaveMetadata(String hash) {
        return "{\"hash\":\"" + hash + "\",\"source\":\"long_term_memory\"}";
    }

    /**
     * 一条语义相近的既有记忆及其相似度。
     */
    public record SimilarMemory(Memory memory, double score) {
    }

}
