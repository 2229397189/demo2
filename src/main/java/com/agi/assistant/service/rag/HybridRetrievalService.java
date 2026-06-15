package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.model.enums.RetrievalStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 * <p>
 * 编排三路检索（稠密向量、稀疏关键词、知识图谱），支持并行执行，
 * 并使用 RRF 融合服务将多路结果合并为统一排序。
 */
@Slf4j
@Lazy
@Service
public class HybridRetrievalService {

    /** 线程池用于并行检索 */
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(4);

    private final MilvusService milvusService;
    private final BM25Service bm25Service;
    private final GraphRetrievalService graphRetrievalService;
    private final RRFFusionService rrfFusionService;
    private final EmbeddingService embeddingService;

    public HybridRetrievalService(MilvusService milvusService,
                                  BM25Service bm25Service,
                                  GraphRetrievalService graphRetrievalService,
                                  RRFFusionService rrfFusionService,
                                  EmbeddingService embeddingService) {
        this.milvusService = milvusService;
        this.bm25Service = bm25Service;
        this.graphRetrievalService = graphRetrievalService;
        this.rrfFusionService = rrfFusionService;
        this.embeddingService = embeddingService;
    }

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 执行检索（使用默认策略 HYBRID）。
     *
     * @param query 查询文本
     * @param topK  最大返回结果数
     * @return 融合后的检索结果
     */
    public List<SearchResult> retrieve(String query, int topK) {
        return retrieve(query, RetrievalStrategy.HYBRID.name(), topK);
    }

    /**
     * 执行检索。
     * <p>
     * 根据指定的策略选择检索路径：
     * <ul>
     *   <li>DENSE: 仅稠密向量检索（Milvus）</li>
     *   <li>SPARSE: 仅稀疏关键词检索（BM25 / Elasticsearch）</li>
     *   <li>GRAPH: 仅知识图谱检索（Neo4j）</li>
     *   <li>HYBRID: 稠密 + 稀疏，RRF 融合</li>
     *   <li>FULL: 稠密 + 稀疏 + 图谱，RRF 融合</li>
     * </ul>
     *
     * @param query    查询文本
     * @param strategy 检索策略名称
     * @param topK     最大返回结果数
     * @return 融合后的检索结果
     */
    public List<SearchResult> retrieve(String query, String strategy, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        RetrievalStrategy strat;
        try {
            strat = RetrievalStrategy.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown retrieval strategy [{}], falling back to HYBRID", strategy);
            strat = RetrievalStrategy.HYBRID;
        }

        log.info("Executing retrieval: strategy={}, query='{}', topK={}",
                strat, query.length() > 50 ? query.substring(0, 50) + "..." : query, topK);

        long startTime = System.currentTimeMillis();
        List<SearchResult> results;

        switch (strat) {
            case DENSE:
                results = executeDense(query, topK);
                break;
            case SPARSE:
                results = executeSparse(query, topK);
                break;
            case GRAPH:
                results = executeGraph(query, topK);
                break;
            case HYBRID:
                results = executeHybrid(query, topK);
                break;
            case FULL:
                results = executeFull(query, topK);
                break;
            default:
                results = executeHybrid(query, topK);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Retrieval completed: strategy={}, results={}, elapsed={}ms",
                strat, results.size(), elapsed);

        return results;
    }

    // ──────────────────────────────────────────────────────────────
    //  单路检索
    // ──────────────────────────────────────────────────────────────

    /**
     * 仅执行稠密向量检索。
     */
    public List<SearchResult> executeDense(String query, int topK) {
        try {
            // 1. 将查询文本转为向量
            List<Float> queryEmbedding = embeddingService.embed(query);
            if (queryEmbedding.isEmpty()) {
                log.warn("Empty embedding for query, skipping dense retrieval");
                return Collections.emptyList();
            }

            // 2. 在 Milvus 中检索
            return milvusService.searchVectors(queryEmbedding, topK);

        } catch (Exception e) {
            log.error("Dense retrieval failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 仅执行稀疏关键词检索。
     */
    public List<SearchResult> executeSparse(String query, int topK) {
        try {
            return bm25Service.search(query, topK);
        } catch (Exception e) {
            log.error("Sparse retrieval failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 仅执行知识图谱检索。
     */
    public List<SearchResult> executeGraph(String query, int topK) {
        try {
            return graphRetrievalService.retrieve(query, topK);
        } catch (Exception e) {
            log.error("Graph retrieval failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  混合检索
    // ──────────────────────────────────────────────────────────────

    /**
     * 执行 HYBRID 策略：稠密 + 稀疏，并行执行后 RRF 融合。
     */
    private List<SearchResult> executeHybrid(String query, int topK) {
        // 并行执行两路检索
        CompletableFuture<List<SearchResult>> denseFuture = CompletableFuture.supplyAsync(
                () -> executeDense(query, topK * 2), retrievalExecutor);
        CompletableFuture<List<SearchResult>> sparseFuture = CompletableFuture.supplyAsync(
                () -> executeSparse(query, topK * 2), retrievalExecutor);

        // 等待两路结果
        CompletableFuture.allOf(denseFuture, sparseFuture).join();

        List<SearchResult> denseResults = getFutureResult(denseFuture, "dense");
        List<SearchResult> sparseResults = getFutureResult(sparseFuture, "sparse");

        log.debug("HYBRID retrieval: dense={}, sparse={}",
                denseResults.size(), sparseResults.size());

        // RRF 融合
        List<List<SearchResult>> rankedLists = new ArrayList<>();
        rankedLists.add(denseResults);
        rankedLists.add(sparseResults);

        return rrfFusionService.fuse(rankedLists, 60, topK);
    }

    /**
     * 执行 FULL 策略：稠密 + 稀疏 + 图谱，并行执行后 RRF 融合。
     */
    private List<SearchResult> executeFull(String query, int topK) {
        // 并行执行三路检索
        CompletableFuture<List<SearchResult>> denseFuture = CompletableFuture.supplyAsync(
                () -> executeDense(query, topK * 2), retrievalExecutor);
        CompletableFuture<List<SearchResult>> sparseFuture = CompletableFuture.supplyAsync(
                () -> executeSparse(query, topK * 2), retrievalExecutor);
        CompletableFuture<List<SearchResult>> graphFuture = CompletableFuture.supplyAsync(
                () -> executeGraph(query, topK), retrievalExecutor);

        // 等待三路结果
        CompletableFuture.allOf(denseFuture, sparseFuture, graphFuture).join();

        List<SearchResult> denseResults = getFutureResult(denseFuture, "dense");
        List<SearchResult> sparseResults = getFutureResult(sparseFuture, "sparse");
        List<SearchResult> graphResults = getFutureResult(graphFuture, "graph");

        log.debug("FULL retrieval: dense={}, sparse={}, graph={}",
                denseResults.size(), sparseResults.size(), graphResults.size());

        // 加权 RRF 融合（图谱权重较低，因为其召回率通常较低）
        List<List<SearchResult>> rankedLists = new ArrayList<>();
        rankedLists.add(denseResults);
        rankedLists.add(sparseResults);
        rankedLists.add(graphResults);

        List<Double> weights = List.of(1.0, 1.0, 0.8);

        return rrfFusionService.fuseWeighted(rankedLists, weights, 60).stream()
                .limit(topK)
                .collect(Collectors.toList());
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 安全获取异步结果，失败时返回空列表。
     */
    private List<SearchResult> getFutureResult(CompletableFuture<List<SearchResult>> future,
                                               String sourceName) {
        try {
            return future.join();
        } catch (Exception e) {
            log.error("{} retrieval future failed: {}", sourceName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
