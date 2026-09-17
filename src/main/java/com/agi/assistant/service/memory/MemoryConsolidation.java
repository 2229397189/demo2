package com.agi.assistant.service.memory;

import com.agi.assistant.mapper.MemoryMapper;
import com.agi.assistant.config.OpenAIConfig;
import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.model.entity.Memory;
import com.agi.assistant.model.enums.MemoryType;
import com.agi.assistant.service.rag.MilvusService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

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
            "- \"topic\": 该事实所属的话题，用于在知识图谱里聚类（如「求职方向」「编程语言偏好」）\n" +
            "- \"source\": 来源（user/ai，标记信息来源）\n\n" +
            "对话内容：\n";

    private static final double SIMILARITY_DEDUP_THRESHOLD = 0.90;
    private static final double DECAY_FACTOR = 0.95;
    private static final double MIN_IMPORTANCE = 0.1;
    private static final int DEFAULT_IMPORTANCE_DECAY_DAYS = 7;

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;
    private final GraphMemory graphMemory;
    private final MemoryMapper memoryMapper;
    private final MilvusService milvusService;
    private final WebClient openAiWebClient;
    private final OpenAIConfig openAIConfig;
    private final ObjectMapper objectMapper;

    /** 记忆事实抽取使用的模型；留空则复用主模型（openai.model） */
    @Value("${app.memory.extraction-model:}")
    private String extractionModel;

    /** 事实抽取的最大输出 token 数 */
    @Value("${app.memory.extraction-max-tokens:3000}")
    private int extractionMaxTokens;

    /** 新记忆的默认 TTL（天） */
    @Value("${app.memory.ttl-days:180}")
    private int ttlDays;

    /** 低重要性记忆的 TTL（天），短于默认 TTL */
    @Value("${app.memory.low-importance-ttl-days:30}")
    private int lowImportanceTtlDays;

    /** 重要性低于该阈值时使用短 TTL */
    @Value("${app.memory.low-importance-threshold:0.5}")
    private double lowImportanceThreshold;

    /** 建立 SIMILAR_TO 图边的相似度阈值 */
    @Value("${app.memory.similar-link-threshold:0.85}")
    private double similarLinkThreshold;

    public MemoryConsolidation(ShortTermMemory shortTermMemory,
                               LongTermMemory longTermMemory,
                               GraphMemory graphMemory,
                               MemoryMapper memoryMapper,
                               MilvusService milvusService,
                               WebClient openAiWebClient,
                               OpenAIConfig openAIConfig) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.graphMemory = graphMemory;
        this.memoryMapper = memoryMapper;
        this.milvusService = milvusService;
        this.openAiWebClient = openAiWebClient;
        this.openAIConfig = openAIConfig;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 解析事实抽取使用的模型名。
     * <p>
     * 修复：此前这里硬编码成 "qwen-turbo"（阿里 DashScope 的模型名），
     * 而 WebClient 的 base-url 指向智谱 GLM，导致每次抽取都因「模型不存在」失败，
     * 记忆整合实际上从未成功写入过任何长期记忆。
     */
    private String resolveExtractionModel() {
        if (extractionModel != null && !extractionModel.isBlank()) {
            return extractionModel.trim();
        }
        return openAIConfig.getModel();
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
     * @param userId    the user identifier
     * @param sessionId the session identifier (used to retrieve short-term memories)
     */
    public void consolidate(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }

        log.info("Starting memory consolidation for user [{}], session [{}]", userId, sessionId);

        try {
            // 1. 取近期消息
            List<ChatMessage> recentMessages = shortTermMemory.getRecentMessages(sessionId, 20);
            if (recentMessages.isEmpty()) {
                log.debug("No recent messages for user [{}], skipping consolidation", userId);
                return;
            }

            // 2. 增量过滤：只处理水位之后的新消息。
            //    修复前每次对话结束都把最近 20 条重新送进 LLM 抽取，既烧 token 又反复命中同一批事实。
            String watermark = shortTermMemory.getConsolidationWatermark(sessionId);
            List<ChatMessage> newMessages = messagesAfterWatermark(recentMessages, watermark);
            if (newMessages.isEmpty()) {
                log.debug("No new messages since watermark for session [{}], skipping consolidation", sessionId);
                return;
            }

            // 3. Extract facts from the new conversation segment
            String conversation = formatConversation(newMessages);
            List<Map<String, Object>> extractedFacts = extractFacts(conversation);

            List<Map<String, Object>> deduplicated = Collections.emptyList();
            int saved = 0;
            if (!extractedFacts.isEmpty()) {
                // 4. Deduplicate
                deduplicated = deduplicate(userId, extractedFacts);

                // 5. Save to long-term memory and graph (含 FOLLOWS / SIMILAR_TO 边)
                saved = persistFacts(userId, deduplicated);
            }

            // 6. 推进水位。即使这一批没抽出事实也要推进，
            //    否则同一批消息下一轮又被送去 LLM。
            shortTermMemory.setConsolidationWatermark(
                    sessionId, fingerprint(recentMessages.get(recentMessages.size() - 1)));

            // 7. 重要性衰减已移交 MemoryMaintenanceScheduler 每日定时执行。
            //    修复前这里每轮整合都调一次 decayImportance(userId)，与定时任务叠加成
            //    「双通道衰减」：同一批 7 天未访问的记忆会被多次 ×0.95，
            //    衰减速度远超设计意图。对话路径只负责写入，衰减统一走定时任务。
            log.info("Consolidation complete for user [{}]: newMessages={}, extracted={}, deduplicated={}, saved={}",
                    userId, newMessages.size(), extractedFacts.size(), deduplicated.size(), saved);

        } catch (Exception e) {
            log.error("Memory consolidation failed for user [{}]: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 删除已过 TTL 的记忆（含向量库中的对应向量）。
     *
     * @return 实际删除条数
     */
    public int purgeExpired() {
        int deleted = 0;
        try {
            LocalDateTime now = LocalDateTime.now();
            while (true) {
                List<Memory> expired = memoryMapper.selectList(new LambdaQueryWrapper<Memory>()
                        .isNotNull(Memory::getExpiresAt)
                        .lt(Memory::getExpiresAt, now)
                        .orderByAsc(Memory::getId)
                        .last("LIMIT 200"));
                if (expired.isEmpty()) {
                    break;
                }

                List<String> vectorIds = new ArrayList<>();
                for (Memory memory : expired) {
                    memoryMapper.deleteById(memory.getId());
                    vectorIds.add("mem_" + memory.getId());
                }

                // 向量库删不动不影响 DB 侧清理，只记 warn（否则孤儿向量会被 recall 召回）
                try {
                    milvusService.deleteByIds(vectorIds);
                } catch (Exception e) {
                    log.warn("删除过期记忆向量失败（数据库记录已删除）: {}", e.getMessage());
                }

                deleted += expired.size();
                if (expired.size() < 200) {
                    break;
                }
            }

            if (deleted > 0) {
                log.info("Purged {} expired memories", deleted);
            }
        } catch (Exception e) {
            log.error("Failed to purge expired memories: {}", e.getMessage(), e);
        }
        return deleted;
    }

    /**
     * 落库一批事实：长期记忆 + 图谱节点 + 话题边 + 时间链边 + 相似边。
     *
     * @return 实际保存条数
     */
    private int persistFacts(Long userId, List<Map<String, Object>> facts) {
        int saved = 0;

        // 与上一轮整合的最后一条记忆串起来，保证 FOLLOWS 链跨会话连续
        // （getMemoryChain 依赖 FOLLOWS，此前这条边从未被写入 → 记忆链恒为空）
        String previousMemoryId = findLatestMemoryId(userId);

        for (Map<String, Object> fact : facts) {
            String content = (String) fact.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String type = (String) fact.getOrDefault("type", "fact");
            double importance = fact.containsKey("importance")
                    ? ((Number) fact.get("importance")).doubleValue() : 0.5;

            Memory memory = longTermMemory.saveMemory(userId, content, type, importance, ttlFor(importance));
            if (memory == null) {
                // 命中去重（哈希或向量相似），跳过
                continue;
            }

            String memoryId = "mem_" + memory.getId();
            graphMemory.addMemoryNode(userId, memoryId, content, importance);

            Object topic = fact.get("topic");
            if (topic instanceof String topicName && !topicName.isBlank()) {
                graphMemory.linkMemoryToTopic(memoryId, topicName);
            }

            if (previousMemoryId != null) {
                graphMemory.linkMemorySequence(previousMemoryId, memoryId);
            }
            previousMemoryId = memoryId;

            linkSimilar(userId, memoryId, content);

            saved++;
        }

        return saved;
    }

    /**
     * 与既有语义相近的记忆建立 SIMILAR_TO 边。
     */
    private void linkSimilar(Long userId, String memoryId, String content) {
        try {
            for (LongTermMemory.SimilarMemory similar
                    : longTermMemory.findSimilar(userId, content, similarLinkThreshold, 5)) {
                String otherId = "mem_" + similar.memory().getId();
                if (otherId.equals(memoryId)) {
                    continue;
                }
                graphMemory.linkSimilarMemories(memoryId, otherId, similar.score());
            }
        } catch (Exception e) {
            log.debug("Failed to link similar memories for [{}]: {}", memoryId, e.getMessage());
        }
    }

    /**
     * 查找用户最近一条长期记忆的图节点 ID，用于接续 FOLLOWS 链。
     */
    private String findLatestMemoryId(Long userId) {
        try {
            Memory latest = memoryMapper.selectOne(new LambdaQueryWrapper<Memory>()
                    .eq(Memory::getUserId, userId)
                    .orderByDesc(Memory::getId)
                    .last("LIMIT 1"));
            return latest != null ? "mem_" + latest.getId() : null;
        } catch (Exception e) {
            log.debug("Failed to find latest memory for user [{}]: {}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 按重要性计算过期时间：低重要性记忆用更短的 TTL；天数为 0 表示永不过期。
     */
    private LocalDateTime ttlFor(double importance) {
        int days = importance < lowImportanceThreshold ? lowImportanceTtlDays : ttlDays;
        return days <= 0 ? null : LocalDateTime.now().plusDays(days);
    }

    /**
     * 截取水位之后的新消息。
     * <p>
     * 水位记录的是「上一条已整合消息的指纹」而不是条数 —— 因为 Redis 列表会被裁剪到
     * 最近 20 条，用条数在水位越界后会永久失效。指纹找不到时退化为全量处理，
     * 靠哈希去重兜底，不会重复落库。
     */
    private List<ChatMessage> messagesAfterWatermark(List<ChatMessage> messages, String watermark) {
        if (watermark == null || watermark.isBlank()) {
            return messages;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (watermark.equals(fingerprint(messages.get(i)))) {
                return new ArrayList<>(messages.subList(i + 1, messages.size()));
            }
        }
        return messages;
    }

    /**
     * 消息指纹：角色 + 时间 + 内容。
     */
    private String fingerprint(ChatMessage message) {
        String raw = message.getRole() + "|"
                + (message.getCreatedAt() != null ? message.getCreatedAt().toString() : "") + "|"
                + (message.getContent() != null ? message.getContent() : "");
        return DigestUtils.sha256Hex(raw).substring(0, 32);
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

            Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("model", resolveExtractionModel());
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", extractionMaxTokens);
            // 事实抽取是结构化 JSON 输出，思维链只会白烧 token 并可能导致内容被截断
            openAIConfig.applyThinking(requestBody);

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

            // Embedding similarity dedup against existing memories.
            // 修复（P2-8）：此前先 recallMemory 召回 3 条，再对每条既有记忆**重新调一次
            // embedding API** 算余弦 —— 每条事实要 1+N 次调用。现在改走 findSimilar：
            // 相似度直接取向量库召回时的 score，总共 1 次 embed + 1 次向量检索。
            try {
                boolean isDuplicate = !longTermMemory
                        .findSimilar(userId, content, SIMILARITY_DEDUP_THRESHOLD, 3)
                        .isEmpty();
                if (isDuplicate) {
                    log.debug("Dedup: semantic duplicate detected for: {}",
                            content.substring(0, Math.min(content.length(), 50)));
                    continue;
                }
            } catch (Exception e) {
                // 向量库不可用时不阻断写入：宁可有少量重复，也不丢用户记忆
                log.debug("Semantic dedup skipped (vector store unavailable): {}", e.getMessage());
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
     * <p>
     * Uses paginated queries to avoid full table scan.
     */
    public void decayImportance() {
        int pageSize = 200;
        int currentPage = 1;
        int totalDecayed = 0;

        try {
            LocalDateTime cutoffDate = LocalDateTime.now()
                    .minusDays(DEFAULT_IMPORTANCE_DECAY_DAYS);

            boolean hasMore = true;
            while (hasMore) {
                // 分页查询：只查 lastAccessedAt < cutoff 的记录，避免全表扫描
                Page<Memory> page = new Page<>(currentPage, pageSize);
                LambdaQueryWrapper<Memory> query = new LambdaQueryWrapper<Memory>()
                        .lt(Memory::getLastAccessedAt, cutoffDate)
                        .isNotNull(Memory::getLastAccessedAt)
                        .orderByAsc(Memory::getId);
                Page<Memory> result = memoryMapper.selectPage(page, query);

                List<Memory> records = result.getRecords();
                if (records == null || records.isEmpty()) {
                    break;
                }

                for (Memory memory : records) {
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
                        totalDecayed++;
                    }
                }

                hasMore = records.size() == pageSize;
                currentPage++;
            }

            log.info("Importance decay applied: {} memories updated", totalDecayed);

        } catch (Exception e) {
            log.error("Importance decay failed: {}", e.getMessage(), e);
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
}
