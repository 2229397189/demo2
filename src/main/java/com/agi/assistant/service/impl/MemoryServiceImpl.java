package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.dto.MemorySearchRequest;
import com.agi.assistant.model.dto.UserProfileDTO;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.enums.MemoryCategory;
import com.agi.assistant.service.MemoryService;
import com.agi.assistant.service.memory.LongTermMemory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MemoryService implementation.
 * <p>
 * Provides user memory querying, embedding-based search,
 * and user profile aggregation.
 */
@Slf4j
@Lazy
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    private final MemoryMapper memoryMapper;
    private final LongTermMemory longTermMemory;

    // ----------------------------------------------------------------
    //  Memory Query
    // ----------------------------------------------------------------

    @Override
    public List<Memory> getUserMemories(Long userId, String type, int limit) {
        if (userId == null) {
            return List.of();
        }

        LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<Memory>()
                .eq(Memory::getUserId, userId);

        if (type != null && !type.isBlank()) {
            // 大小写不敏感筛选（修复「按类型筛选永远为空」）：
            // 输入先归一为大写规范 token，再与 UPPER(type) 比较 —— fact / FACT / Fact
            // 三种写法都能命中同一条大写存储的记录，历史小写数据（"fact"）也能被筛出来。
            wrapper.apply("UPPER(type) = {0}", normalizeMemoryType(type));
        }

        wrapper.orderByDesc(Memory::getImportance)
                .orderByDesc(Memory::getLastAccessedAt);

        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }

        List<Memory> memories = memoryMapper.selectList(wrapper);
        log.debug("Retrieved {} memories for user [{}], type={}", memories.size(), userId, type);
        return memories;
    }

    /**
     * 把「记忆类型」筛选参数归一为规范大写 token。
     * <p>
     * 抽成独立方法的原因：让「筛选口径是否统一」这件事可以被单测直接断言，
     * 而不依赖 MyBatis-Plus 内部把 {@code apply("...{0}", val)} 的参数暴露出来
     * （该参数并不进入 {@code paramNameValuePairs}，纯单测环境无法直接读取）。
     *
     * @param raw 前端/调用方传入的原始类型串（大小写、甚至历史旧值均可）
     * @return 5 个规范 token 之一（大写）
     */
    static String normalizeMemoryType(String raw) {
        return MemoryCategory.normalize(raw);
    }

    // ----------------------------------------------------------------
    //  Memory Search
    // ----------------------------------------------------------------

    @Override
    public List<Memory> searchMemories(MemorySearchRequest request) {
        if (request == null || request.getUserId() == null
                || request.getQuery() == null || request.getQuery().isBlank()) {
            return List.of();
        }

        int topK = request.getTopK() > 0 ? request.getTopK() : 10;

        // Use LongTermMemory for embedding-based recall
        List<String> recalledContents = longTermMemory.recallMemory(
                request.getUserId(), request.getQuery(), topK);

        if (recalledContents.isEmpty()) {
            return List.of();
        }

        // Look up the full Memory entities for the recalled contents
        // Use selectList to handle potential multiple matches safely
        List<Memory> results = new ArrayList<>();
        for (String content : recalledContents) {
            if (content == null || content.isBlank()) {
                continue;
            }
            // Escape LIKE special characters and use a reasonable prefix
            String searchPrefix = content.substring(0, Math.min(content.length(), 200));
            searchPrefix = escapeLikeSpecialChars(searchPrefix);

            LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, request.getUserId())
                    .like(Memory::getContent, searchPrefix)
                    .last("LIMIT 1");
            List<Memory> memories = memoryMapper.selectList(wrapper);
            if (!memories.isEmpty()) {
                results.add(memories.get(0));
            }
        }

        log.debug("Search memories for user [{}]: query='{}', found={}",
                request.getUserId(), request.getQuery(), results.size());
        return results;
    }

    // ----------------------------------------------------------------
    //  User Profile
    // ----------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public UserProfileDTO getUserProfile(Long userId) {
        if (userId == null) {
            return new UserProfileDTO();
        }

        Map<String, Object> profileData = longTermMemory.getUserProfile(userId);

        UserProfileDTO profile = new UserProfileDTO();
        profile.setUserId(userId);

        // Set total memories and average importance from profile data
        if (profileData.containsKey("totalMemories")) {
            profile.setTotalMemories(((Number) profileData.get("totalMemories")).intValue());
        }
        if (profileData.containsKey("averageImportance")) {
            profile.setAverageImportance(((Number) profileData.get("averageImportance")).doubleValue());
        }
        if (profileData.containsKey("topAccessedMemories")) {
            profile.setTopAccessedMemories((List<String>) profileData.get("topAccessedMemories"));
        }

        // Extract topics and memoriesByType
        Map<String, List<String>> byType = (Map<String, List<String>>)
                profileData.getOrDefault("memoriesByType", Map.of());
        profile.setMemoriesByType(byType);
        profile.setTopics(new ArrayList<>(byType.keySet()));

        // Set knowledge level based on memory types and counts
        profile.setKnowledgeLevel(new java.util.HashMap<>());
        for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
            String level = entry.getValue().size() > 10 ? "advanced"
                    : entry.getValue().size() > 5 ? "intermediate" : "beginner";
            profile.getKnowledgeLevel().put(entry.getKey(), level);
        }

        log.debug("Built user profile for user [{}]: topics={}, totalMemories={}",
                userId, profile.getTopics(), profile.getTotalMemories());
        return profile;
    }

    /**
     * Escape special LIKE characters (%, _, \) in search strings.
     */
    private String escapeLikeSpecialChars(String str) {
        if (str == null) return null;
        return str.replace("\\", "\\\\")
                  .replace("%", "\\%")
                  .replace("_", "\\_");
    }
}
