package com.agi.assistant.service.memory;

import com.agi.assistant.model.entity.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Short-term memory service.
 * <p>
 * Manages recent N rounds of conversation context per session using Redis.
 * Each session maintains a bounded list of recent messages (default max 20).
 */
@Slf4j
@Service
public class ShortTermMemory {

    private static final String KEY_PREFIX = "stm:session:";
    private static final String WATERMARK_PREFIX = "stm:watermark:";
    private static final int DEFAULT_MAX_MESSAGES = 20;
    private static final long DEFAULT_TTL_HOURS = 24;
    /** 记忆整合水位的有效期：略长于会话本身的 TTL，避免会话还在但水位先过期 */
    private static final long WATERMARK_TTL_HOURS = 72;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public ShortTermMemory(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Add a message to the session's short-term memory.
     * Maintains a sliding window of the most recent messages.
     *
     * @param sessionId the session identifier
     * @param message   the chat message to store
     */
    public void addMessage(String sessionId, ChatMessage message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }

        String key = buildKey(sessionId);
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, DEFAULT_TTL_HOURS, TimeUnit.HOURS);

            // Trim to max size
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > DEFAULT_MAX_MESSAGES) {
                redisTemplate.opsForList().trim(key, size - DEFAULT_MAX_MESSAGES, size - 1);
            }

            log.debug("Added message to session [{}]: role={}, contentLength={}",
                    sessionId, message.getRole(),
                    message.getContent() != null ? message.getContent().length() : 0);

        } catch (Exception e) {
            log.error("Failed to add message to session [{}]: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Retrieve recent messages for a session.
     *
     * @param sessionId the session identifier
     * @param limit     maximum number of messages to return (0 for all stored)
     * @return list of recent messages, ordered oldest to newest
     */
    public List<ChatMessage> getRecentMessages(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }

        String key = buildKey(sessionId);
        try {
            Long size = redisTemplate.opsForList().size(key);
            if (size == null || size == 0) {
                return Collections.emptyList();
            }

            int effectiveLimit = (limit <= 0 || limit > size) ? size.intValue() : limit;
            long start = size - effectiveLimit;

            List<Object> rawList = redisTemplate.opsForList().range(key, start, size - 1);
            if (rawList == null || rawList.isEmpty()) {
                return Collections.emptyList();
            }

            List<ChatMessage> messages = new ArrayList<>(rawList.size());
            for (Object raw : rawList) {
                String json = raw instanceof String ? (String) raw : raw.toString();
                messages.add(objectMapper.readValue(json, ChatMessage.class));
            }

            log.debug("Retrieved {} messages from session [{}]", messages.size(), sessionId);
            return messages;

        } catch (Exception e) {
            log.error("Failed to get messages from session [{}]: {}", sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Clear all messages for a session.
     *
     * @param sessionId the session identifier
     */
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        String key = buildKey(sessionId);
        try {
            redisTemplate.delete(key);
            log.info("Cleared short-term memory for session [{}]", sessionId);
        } catch (Exception e) {
            log.error("Failed to clear session [{}]: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Get the current message count for a session.
     *
     * @param sessionId the session identifier
     * @return number of messages stored
     */
    public long getSessionSize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }

        String key = buildKey(sessionId);
        try {
            Long size = redisTemplate.opsForList().size(key);
            return size != null ? size : 0;
        } catch (Exception e) {
            log.error("Failed to get size for session [{}]: {}", sessionId, e.getMessage(), e);
            return 0;
        }
    }

    // ----------------------------------------------------------------
    //  记忆整合水位
    // ----------------------------------------------------------------

    /**
     * 读取记忆整合水位（上一次已整合的最后一条消息指纹）。
     * <p>
     * 没有水位时返回 null，调用方应视为「从未整合过」。
     * Redis 不可用时同样返回 null —— 退化为全量整合，靠哈希去重兜底，不会重复落库。
     *
     * @param sessionId 会话 ID
     * @return 指纹字符串或 null
     */
    public String getConsolidationWatermark(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(WATERMARK_PREFIX + sessionId);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.debug("Failed to read consolidation watermark for session [{}]: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入记忆整合水位。
     *
     * @param sessionId   会话 ID
     * @param fingerprint 最后一条已整合消息的指纹
     */
    public void setConsolidationWatermark(String sessionId, String fingerprint) {
        if (sessionId == null || sessionId.isBlank() || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    WATERMARK_PREFIX + sessionId, fingerprint, WATERMARK_TTL_HOURS, TimeUnit.HOURS);
            log.debug("Updated consolidation watermark for session [{}]: {}", sessionId, fingerprint);
        } catch (Exception e) {
            log.debug("Failed to write consolidation watermark for session [{}]: {}", sessionId, e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    //  Internal
    // ----------------------------------------------------------------

    private String buildKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
