package com.agi.assistant.service.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内、<b>有界</b>的检索结果缓存。
 * <p>
 * 用途：作为「检索失败时的降级路之一」—— 当在线检索 / 向量检索整体不可用时，
 * 直接从最近一次成功的检索结果里取数据，避免整条对话链彻底断掉。
 * <p>
 * <b>容量策略</b>：默认容量固定为 {@value #DEFAULT_CAPACITY} 条（见 {@link #DEFAULT_CAPACITY}）。
 * 之所以写死一个较小值而非「无界」：缓存一旦无界增长，部署到服务器后会随查询量
 * 单调上升直至 OOM —— 这是典型的内存安全问题。128 条足以覆盖单机会话的热点查询，
 * 又被 LRU 淘汰保住了内存上界。需要其它容量时用 {@link #RetrievalResultCache(int)} 构造。
 * <p>
 * <b>淘汰策略</b>：基于 {@link LinkedHashMap} 的访问序（access-order）+ {@code removeEldestEntry}，
 * 即标准 LRU：命中即刷新新鲜度，容量超限时淘汰最久未使用项。
 * <p>
 * <b>线程安全</b>：所有读写方法均 {@code synchronized}。缓存读写属观测 / 优化埋点，
 * 内部异常一律吞掉，绝不抛给调用方。
 */
@Slf4j
@Component
public class RetrievalResultCache {

    /**
     * 默认缓存容量（条数）。写死 128：既覆盖单机会话热点，又保证内存有上界。
     */
    private static final int DEFAULT_CAPACITY = 128;

    /** 实际生效的容量上界（>0） */
    private final int capacity;

    /** LRU 存储：访问序 LinkedHashMap，由 removeEldestEntry 保证有界 */
    private final Map<String, List<?>> store;

    public RetrievalResultCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 以指定容量构造缓存（容量非法时回退到 {@link #DEFAULT_CAPACITY}）。
     *
     * @param capacity 容量上界（条数）
     */
    public RetrievalResultCache(int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        // accessOrder = true → 访问序，get/put 命中都会把条目移到队尾（最近使用）
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<?>> eldest) {
                return size() > RetrievalResultCache.this.capacity;
            }
        };
    }

    /**
     * 写入一条检索结果。
     *
     * @param query   查询串（作为键）；为 null / 空白时忽略
     * @param results 检索结果；为 null 时忽略
     */
    public synchronized void put(String query, List<?> results) {
        if (query == null || query.isBlank() || results == null) {
            return;
        }
        try {
            store.put(query, results);
            log.debug("Retrieval cache put: queryLength={}, size={}/{}",
                    query.length(), store.size(), capacity);
        } catch (Exception e) {
            log.debug("Retrieval cache put failed (ignored): {}", e.getMessage());
        }
    }

    /**
     * 读取一条检索结果（LRU：命中即刷新新鲜度）。
     *
     * @param query 查询串
     * @return 命中的结果列表；未命中或参数非法返回 null
     */
    public synchronized List<?> get(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            return store.get(query);
        } catch (Exception e) {
            log.debug("Retrieval cache get failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 当前缓存条目数。
     *
     * @return 已缓存的条目数
     */
    public synchronized int size() {
        return store.size();
    }

    /**
     * 清空缓存。
     */
    public synchronized void clear() {
        store.clear();
        log.debug("Retrieval cache cleared");
    }
}
