package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.model.dto.MemorySearchRequest;
import com.agi.assistant.model.dto.UserProfileDTO;
import com.agi.assistant.model.entity.Memory;
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
            wrapper.eq(Memory::getType, type);
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
        List<Memory> results = new ArrayList<>();
        for (String content : recalledContents) {
            LambdaQueryWrapper<Memory> wrapper = new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, request.getUserId())
                    .like(Memory::getContent, content.substring(0, Math.min(content.length(), 100)))
                    .last("LIMIT 1");
            Memory memory = memoryMapper.selectOne(wrapper);
            if (memory != null) {
                results.add(memory);
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

        if (profileData.containsKey("totalMemories")) {
            int total = ((Number) profileData.get("totalMemories")).intValue();
            profile.setTopics(new ArrayList<>());

            // Extract topics from memories by type
            Map<String, List<String>> byType = (Map<String, List<String>>)
                    profileData.getOrDefault("memoriesByType", Map.of());
            List<String> topics = new ArrayList<>(byType.keySet());
            profile.setTopics(topics);
        }

        // Set knowledge level based on memory types and counts
        Map<String, List<String>> byType = (Map<String, List<String>>)
                profileData.getOrDefault("memoriesByType", Map.of());
        profile.setKnowledgeLevel(new java.util.HashMap<>());
        for (Map.Entry<String, List<String>> entry : byType.entrySet()) {
            String level = entry.getValue().size() > 10 ? "advanced"
                    : entry.getValue().size() > 5 ? "intermediate" : "beginner";
            profile.getKnowledgeLevel().put(entry.getKey(), level);
        }

        log.debug("Built user profile for user [{}]: topics={}", userId, profile.getTopics());
        return profile;
    }
}
