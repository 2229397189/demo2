package com.agi.assistant.service.evaluation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 检索评估器
 * <p>
 * 基于标准信息检索指标评估检索质量：
 * <ul>
 *   <li>Recall@K：前 K 个结果中命中相关文档的比例</li>
 *   <li>Precision@K：前 K 个结果中相关文档的比例</li>
 *   <li>MRR (Mean Reciprocal Rank)：第一个相关文档排名的倒数</li>
 *   <li>NDCG@K (Normalized Discounted Cumulative Gain)：考虑排名位置的增益</li>
 *   <li>HitRate：是否至少命中一个相关文档</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalEvaluator {

    /**
     * 评估检索结果。
     *
     * @param retrievedDocIds 检索返回的文档 ID 列表（按相关性排序）
     * @param expectedDocIds  期望命中的相关文档 ID 列表
     * @param k               截断位置 K
     * @return 检索指标
     */
    public RetrievalMetrics evaluate(List<String> retrievedDocIds,
                                     List<String> expectedDocIds,
                                     int k) {
        if (retrievedDocIds == null) retrievedDocIds = List.of();
        if (expectedDocIds == null) expectedDocIds = List.of();
        if (k <= 0) k = 10;

        // 取前 K 个结果
        List<String> topK = retrievedDocIds.subList(0, Math.min(k, retrievedDocIds.size()));
        Set<String> expectedSet = new HashSet<>(expectedDocIds);

        double recallAtK = calculateRecallAtK(topK, expectedSet);
        double precisionAtK = calculatePrecisionAtK(topK, expectedSet);
        double mrr = calculateMRR(retrievedDocIds, expectedSet);
        double ndcgAtK = calculateNDCGAtK(topK, expectedSet, k);
        double hitRate = calculateHitRate(topK, expectedSet);

        RetrievalMetrics metrics = new RetrievalMetrics(recallAtK, precisionAtK, mrr, ndcgAtK, hitRate, k);

        log.debug("Retrieval metrics: {}", metrics);
        return metrics;
    }

    /**
     * Recall@K = (命中数) / (相关文档总数)
     */
    private double calculateRecallAtK(List<String> topK, Set<String> expectedSet) {
        if (expectedSet.isEmpty()) return 1.0;

        long hits = topK.stream().filter(expectedSet::contains).count();
        return (double) hits / expectedSet.size();
    }

    /**
     * Precision@K = (命中数) / K
     */
    private double calculatePrecisionAtK(List<String> topK, Set<String> expectedSet) {
        if (topK.isEmpty()) return 0.0;

        long hits = topK.stream().filter(expectedSet::contains).count();
        return (double) hits / topK.size();
    }

    /**
     * MRR = 1 / rank_of_first_relevant_document
     */
    private double calculateMRR(List<String> retrieved, Set<String> expectedSet) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expectedSet.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * NDCG@K = DCG@K / IDCG@K
     * <p>
     * DCG@K = sum(rel_i / log2(i+1))，二元相关性下 rel_i = 0 或 1
     * IDCG@K 是理想排序下的 DCG
     */
    private double calculateNDCGAtK(List<String> topK, Set<String> expectedSet, int k) {
        double dcg = 0.0;
        for (int i = 0; i < topK.size(); i++) {
            double rel = expectedSet.contains(topK.get(i)) ? 1.0 : 0.0;
            dcg += rel / log2(i + 2); // i+2 因为 log2(1)=0，rank 从 1 开始
        }

        // 理想排序：所有相关文档排在最前面
        int relevantCount = Math.min(expectedSet.size(), k);
        double idcg = 0.0;
        for (int i = 0; i < relevantCount; i++) {
            idcg += 1.0 / log2(i + 2);
        }

        return idcg > 0 ? dcg / idcg : 0.0;
    }

    /**
     * HitRate = 1 if any relevant doc found in top K, else 0
     */
    private double calculateHitRate(List<String> topK, Set<String> expectedSet) {
        return topK.stream().anyMatch(expectedSet::contains) ? 1.0 : 0.0;
    }

    private double log2(int n) {
        return Math.log(n) / Math.log(2);
    }

    // ──────────────────────────────────────────────────────────────
    //  结果类
    // ──────────────────────────────────────────────────────────────

    /**
     * 检索指标结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetrievalMetrics {

        /** Recall@K */
        private double recallAtK;

        /** Precision@K */
        private double precisionAtK;

        /** Mean Reciprocal Rank */
        private double mrr;

        /** Normalized Discounted Cumulative Gain@K */
        private double ndcgAtK;

        /** Hit Rate */
        private double hitRate;

        /** 使用的 K 值 */
        private int k;

        @Override
        public String toString() {
            return String.format(
                    "Recall@%d=%.4f, Precision@%d=%.4f, MRR=%.4f, NDCG@%d=%.4f, HitRate=%.4f",
                    k, recallAtK, k, precisionAtK, mrr, k, ndcgAtK, hitRate);
        }
    }
}
