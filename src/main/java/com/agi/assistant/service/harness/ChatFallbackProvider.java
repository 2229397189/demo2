package com.agi.assistant.service.harness;

import com.agi.assistant.model.entity.ChatMessage;
import com.agi.assistant.service.memory.ShortTermMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对话降级供应商集合。
 * <p>
 * 把「每类操作的多级降级路」集中在一处，供上层 {@code HarnessRuntime} / 业务编排在
 * 主路径失败时调用。设计原则：
 * <ul>
 *   <li>每一路都只做「本地 / 就近」的降级动作，绝不在降级路上再触发一串外部依赖
 *       （例如内存降级只读短期记忆，不碰长期记忆与知识图谱）；</li>
 *   <li>每一路都 try-catch，失败往下一路走，最差返回「空结果」而非编造数据；</li>
 *   <li>降级链的编排复用 {@link FallbackStrategy#executeChain}，与运行时保持同一套语义。</li>
 * </ul>
 */
@Slf4j
@Component
public class ChatFallbackProvider {

    private final RetrievalResultCache retrievalResultCache;
    private final ShortTermMemory shortTermMemory;
    private final FallbackStrategy fallbackStrategy;

    public ChatFallbackProvider(RetrievalResultCache retrievalResultCache,
                                ShortTermMemory shortTermMemory,
                                FallbackStrategy fallbackStrategy) {
        this.retrievalResultCache = retrievalResultCache;
        this.shortTermMemory = shortTermMemory;
        this.fallbackStrategy = fallbackStrategy;
    }

    /**
     * 检索降级：① 先查进程内 {@link RetrievalResultCache}；② 未命中返回空列表。
     * <p>
     * 语义上「空列表」表示「本次降级没有可用检索结果」，<b>不是</b>编造内容。
     *
     * @param query 查询串
     * @return 缓存命中的结果；否则空列表
     */
    public List<Object> retrievalFallback(String query) {
        // 一级：进程内缓存命中；未命中时抛异常以驱动降级链进入下一级
        Supplier<List<Object>> cacheTier = () -> {
            List<Object> cached = copyOf(retrievalResultCache.get(query));
            if (cached.isEmpty()) {
                throw new IllegalStateException("retrieval result cache miss");
            }
            return cached;
        };
        // 二级：空列表兜底
        List<Object> result = fallbackStrategy.executeChain(
                cacheTier,
                List.of(Collections::emptyList),
                "retrievalFallback");
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 联网搜索降级：① 先查进程内 {@link RetrievalResultCache}；② 未命中返回空列表。
     *
     * @param query 查询串
     * @return 缓存命中的结果；否则空列表
     */
    public List<Object> webSearchFallback(String query) {
        Supplier<List<Object>> cacheTier = () -> {
            List<Object> cached = copyOf(retrievalResultCache.get(query));
            if (cached.isEmpty()) {
                throw new IllegalStateException("web-search result cache miss");
            }
            return cached;
        };
        List<Object> result = fallbackStrategy.executeChain(
                cacheTier,
                List.of(Collections::emptyList),
                "webSearchFallback");
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 记忆降级：① 用 {@link ShortTermMemory} <b>现拼一个最小上下文</b>（只查短期记忆，
     * 不查长期记忆 / 知识图谱，避免降级路上再触发一串外部依赖）；② 失败返回空 Map。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param query     查询串
     * @return 最小可用上下文；失败返回空 Map
     */
    public Map<String, Object> memoryFallback(Long userId, String sessionId, String query) {
        Supplier<Map<String, Object>> shortTermTier = () -> {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("userId", userId);
            context.put("query", query);
            List<ChatMessage> recent = shortTermMemory.getRecentMessages(sessionId, 0);
            if (recent != null && !recent.isEmpty()) {
                context.put("recentMessages", recent);
                context.put("messageCount", recent.size());
            }
            context.put("degraded", true);
            context.put("source", "short-term-memory-only");
            log.info("Memory fallback assembled minimal context from short-term memory: "
                            + "sessionId={}, messages={}",
                    sessionId, recent == null ? 0 : recent.size());
            return context;
        };
        Map<String, Object> result = fallbackStrategy.executeChain(
                shortTermTier,
                List.of(Collections::emptyMap),
                "memoryFallback");
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * ReAct 降级：返回空串 {@code ""}，语义为「降级为走普通流式回答」。
     *
     * @return 空串
     */
    public String reactFallback() {
        log.info("React fallback selected: degrading to plain streaming answer");
        return "";
    }

    /**
     * 把缓存中的 {@code List<?>} 复制成一个元素为 {@code Object} 的列表，
     * 避免把内部引用直接泄漏给调用方，同时满足 {@code List<Object>} 的返回类型。
     *
     * @param source 缓存取出的列表，可为 null
     * @return 复制后的列表；source 为 null / 空时返回空列表
     */
    private List<Object> copyOf(List<?> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> copy = new ArrayList<>(source.size());
        for (Object item : source) {
            copy.add(item);
        }
        return copy;
    }
}
