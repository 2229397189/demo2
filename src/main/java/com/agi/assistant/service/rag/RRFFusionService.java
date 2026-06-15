package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 倒数排名融合（Reciprocal Rank Fusion, RRF）服务
 * <p>
 * 将多个检索系统的排序结果合并为一个统一排序。
 * <p>
 * RRF 公式：<pre>RRF_score(d) = Σ 1/(k + rank_i(d))</pre>
 * 其中 k 为常数（默认 60），rank_i(d) 为文档 d 在第 i 个检索列表中的排名（从 1 开始）。
 */
@Slf4j
@Service
public class RRFFusionService {

    /** 默认 RRF 常数 k */
    private static final int DEFAULT_K = 60;

    // ──────────────────────────────────────────────────────────────
    //  公共 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 使用默认 k=60 融合多路检索结果。
     *
     * @param rankedLists 多路检索结果列表，每个子列表已按相关性降序排列
     * @return 融合后的结果列表，按 RRF 分数降序排列
     */
    public List<SearchResult> fuse(List<List<SearchResult>> rankedLists) {
        return fuse(rankedLists, DEFAULT_K);
    }

    /**
     * 使用指定 k 值融合多路检索结果。
     * <p>
     * 对每个检索列表中的每个文档，计算 RRF_score = Σ 1/(k + rank)，
     * 然后按 RRF 分数降序排列。
     *
     * @param rankedLists 多路检索结果列表，每个子列表已按相关性降序排列
     * @param k           RRF 常数，控制排名靠后文档的权重衰减速度，建议值 60
     * @return 融合后的结果列表，按 RRF 分数降序排列
     */
    public List<SearchResult> fuse(List<List<SearchResult>> rankedLists, int k) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return Collections.emptyList();
        }

        // 过滤空列表
        List<List<SearchResult>> nonEmptyLists = rankedLists.stream()
                .filter(list -> list != null && !list.isEmpty())
                .collect(Collectors.toList());

        if (nonEmptyLists.isEmpty()) {
            return Collections.emptyList();
        }

        if (nonEmptyLists.size() == 1) {
            // 只有一路结果，直接返回
            return new ArrayList<>(nonEmptyLists.get(0));
        }

        // 计算每个文档的 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, SearchResult> bestMatch = new HashMap<>();

        for (List<SearchResult> rankedList : nonEmptyLists) {
            for (int rank = 0; rank < rankedList.size(); rank++) {
                SearchResult result = rankedList.get(rank);
                String docKey = buildDocKey(result);

                // RRF 分数累加：1/(k + rank)，rank 从 1 开始
                double rrfContribution = 1.0 / (k + rank + 1);
                rrfScores.merge(docKey, rrfContribution, Double::sum);

                // 记录原始分数最高的匹配
                bestMatch.merge(docKey, result, (existing, incoming) -> {
                    if (incoming.getScore() > existing.getScore()) {
                        return incoming;
                    }
                    return existing;
                });
            }
        }

        // 构建融合结果
        List<SearchResult> fusedResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            String docKey = entry.getKey();
            double rrfScore = entry.getValue();
            SearchResult original = bestMatch.get(docKey);

            if (original != null) {
                SearchResult fused = SearchResult.builder()
                        .documentId(original.getDocumentId())
                        .chunkIndex(original.getChunkIndex())
                        .content(original.getContent())
                        .score(rrfScore)
                        .source(original.getSource())
                        .title(original.getTitle())
                        .metadata(original.getMetadata())
                        .build();
                fusedResults.add(fused);
            }
        }

        // 按 RRF 分数降序排列
        fusedResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.debug("RRF fusion: {} input lists, {} unique results, top score={}",
                nonEmptyLists.size(), fusedResults.size(),
                fusedResults.isEmpty() ? 0 : fusedResults.get(0).getScore());

        return fusedResults;
    }

    /**
     * 融合多路检索结果并限制返回数量。
     *
     * @param rankedLists 多路检索结果列表
     * @param k           RRF 常数
     * @param topK        最大返回结果数
     * @return 融合后的结果列表（最多 topK 个）
     */
    public List<SearchResult> fuse(List<List<SearchResult>> rankedLists, int k, int topK) {
        List<SearchResult> fused = fuse(rankedLists, k);
        if (fused.size() > topK) {
            return fused.subList(0, topK);
        }
        return fused;
    }

    /**
     * 使用加权 RRF 融合多路检索结果。
     * <p>
     * 不同检索来源可以赋予不同权重：
     * <pre>RRF_score(d) = Σ w_i / (k + rank_i(d))</pre>
     *
     * @param rankedLists 多路检索结果列表
     * @param weights     每路检索的权重列表（与 rankedLists 一一对应）
     * @param k           RRF 常数
     * @return 融合后的结果列表
     */
    public List<SearchResult> fuseWeighted(List<List<SearchResult>> rankedLists,
                                           List<Double> weights, int k) {
        if (rankedLists == null || rankedLists.isEmpty()) {
            return Collections.emptyList();
        }

        if (weights == null || weights.size() != rankedLists.size()) {
            log.warn("Weights size mismatch, falling back to uniform weights");
            return fuse(rankedLists, k);
        }

        // 过滤空列表并同步权重
        List<List<SearchResult>> nonEmptyLists = new ArrayList<>();
        List<Double> nonEmptyWeights = new ArrayList<>();
        for (int i = 0; i < rankedLists.size(); i++) {
            if (rankedLists.get(i) != null && !rankedLists.get(i).isEmpty()) {
                nonEmptyLists.add(rankedLists.get(i));
                nonEmptyWeights.add(weights.get(i));
            }
        }

        if (nonEmptyLists.isEmpty()) {
            return Collections.emptyList();
        }

        // 计算加权 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, SearchResult> bestMatch = new HashMap<>();

        for (int listIdx = 0; listIdx < nonEmptyLists.size(); listIdx++) {
            List<SearchResult> rankedList = nonEmptyLists.get(listIdx);
            double weight = nonEmptyWeights.get(listIdx);

            for (int rank = 0; rank < rankedList.size(); rank++) {
                SearchResult result = rankedList.get(rank);
                String docKey = buildDocKey(result);

                double rrfContribution = weight / (k + rank + 1);
                rrfScores.merge(docKey, rrfContribution, Double::sum);

                bestMatch.merge(docKey, result, (existing, incoming) -> {
                    if (incoming.getScore() > existing.getScore()) {
                        return incoming;
                    }
                    return existing;
                });
            }
        }

        // 构建融合结果
        List<SearchResult> fusedResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rrfScores.entrySet()) {
            String docKey = entry.getKey();
            double rrfScore = entry.getValue();
            SearchResult original = bestMatch.get(docKey);

            if (original != null) {
                // 合并来源信息
                String source = original.getSource();
                if (source == null) {
                    source = "fused";
                }

                SearchResult fused = SearchResult.builder()
                        .documentId(original.getDocumentId())
                        .chunkIndex(original.getChunkIndex())
                        .content(original.getContent())
                        .score(rrfScore)
                        .source(source)
                        .title(original.getTitle())
                        .metadata(original.getMetadata())
                        .build();
                fusedResults.add(fused);
            }
        }

        fusedResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.debug("Weighted RRF fusion: {} input lists, {} unique results",
                nonEmptyLists.size(), fusedResults.size());

        return fusedResults;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 构建文档唯一标识键（用于去重合并）。
     */
    private String buildDocKey(SearchResult result) {
        return result.getDocumentId() + "::" + result.getChunkIndex();
    }
}
