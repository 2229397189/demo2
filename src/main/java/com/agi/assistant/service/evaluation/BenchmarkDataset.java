package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.GoldenQueryMapper;
import com.agi.assistant.model.entity.GoldenQuery;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 基准数据集管理服务
 * <p>
 * 管理评测基准数据集和黄金查询（Golden Query），
 * 提供数据集加载、查询检索、新增查询等功能。
 */
@Slf4j
@Service
public class BenchmarkDataset {

    private final ObjectMapper objectMapper;
    private final GoldenQueryMapper goldenQueryMapper;

    /** 数据集缓存：datasetId → GoldenQuery 列表 */
    private final Map<String, List<GoldenQuery>> datasetCache = new ConcurrentHashMap<>();

    /** 自增 ID 生成器 */
    private final AtomicLong idGenerator = new AtomicLong(1);

    public BenchmarkDataset(ObjectMapper objectMapper, GoldenQueryMapper goldenQueryMapper) {
        this.objectMapper = objectMapper;
        this.goldenQueryMapper = goldenQueryMapper;
        initSampleDataset();
    }

    /**
     * 列出所有可用数据集及其查询数量。
     *
     * @return 数据集信息列表，每项包含 datasetId 和 queryCount
     */
    public List<Map<String, Object>> listDatasets() {
        List<GoldenQuery> allQueries = goldenQueryMapper.selectList(null);
        Map<String, Long> countByDataset = allQueries.stream()
                .collect(Collectors.groupingBy(GoldenQuery::getDatasetId, Collectors.counting()));

        return countByDataset.entrySet().stream()
                .map(e -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("datasetId", e.getKey());
                    info.put("queryCount", e.getValue());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * 加载指定数据集。
     *
     * @param datasetId 数据集 ID
     * @return 数据集中的 GoldenQuery 列表
     */
    public List<GoldenQuery> loadDataset(String datasetId) {
        if (datasetId == null) {
            log.warn("Null datasetId, returning empty list");
            return List.of();
        }

        List<GoldenQuery> queries = datasetCache.get(datasetId);
        if (queries != null) {
            log.info("Loaded dataset [{}] from cache: {} queries", datasetId, queries.size());
            return Collections.unmodifiableList(queries);
        }

        // Cache miss — fall back to database
        List<GoldenQuery> dbQueries = goldenQueryMapper.selectList(
                new LambdaQueryWrapper<GoldenQuery>()
                        .eq(GoldenQuery::getDatasetId, datasetId));
        if (dbQueries != null && !dbQueries.isEmpty()) {
            datasetCache.put(datasetId, dbQueries);
            log.info("Loaded dataset [{}] from database: {} queries", datasetId, dbQueries.size());
            return Collections.unmodifiableList(dbQueries);
        }

        log.warn("Dataset [{}] not found in cache or database", datasetId);
        return List.of();
    }

    /**
     * 获取指定数据集的黄金查询。
     *
     * @param datasetId 数据集 ID
     * @return GoldenQuery 列表
     */
    public List<GoldenQuery> getGoldenQueries(String datasetId) {
        return loadDataset(datasetId);
    }

    /**
     * 添加黄金查询到数据集。
     *
     * @param datasetId      数据集 ID
     * @param query          查询文本
     * @param expectedAnswer 期望答案
     * @param relevantDocIds 相关文档 ID 列表（JSON 字符串）
     * @param difficulty     难度等级
     * @param category       分类
     * @return 创建的 GoldenQuery
     */
    public GoldenQuery addGoldenQuery(String datasetId, String query, String expectedAnswer,
                                       String relevantDocIds, String difficulty, String category) {
        GoldenQuery goldenQuery = new GoldenQuery();
        goldenQuery.setId(idGenerator.getAndIncrement());
        goldenQuery.setDatasetId(datasetId);
        goldenQuery.setQuery(query);
        goldenQuery.setExpectedAnswer(expectedAnswer);
        goldenQuery.setRelevantDocIds(relevantDocIds);
        goldenQuery.setDifficulty(difficulty);
        goldenQuery.setCategory(category);
        goldenQuery.setCreatedAt(LocalDateTime.now());

        // 保存到数据库
        try {
            goldenQueryMapper.insert(goldenQuery);
            log.info("Saved golden query to database: id={}", goldenQuery.getId());
        } catch (Exception e) {
            log.warn("Failed to save golden query to database: {}", e.getMessage());
        }

        // 同时保存到缓存
        datasetCache.computeIfAbsent(datasetId, k -> new ArrayList<>()).add(goldenQuery);

        log.info("Added golden query to dataset [{}]: query='{}', id={}",
                datasetId, truncate(query, 50), goldenQuery.getId());

        return goldenQuery;
    }

    /**
     * 批量添加黄金查询。
     *
     * @param datasetId   数据集 ID
     * @param goldenQueries GoldenQuery 列表
     */
    public void addGoldenQueries(String datasetId, List<GoldenQuery> goldenQueries) {
        if (goldenQueries == null || goldenQueries.isEmpty()) {
            return;
        }

        List<GoldenQuery> dataset = datasetCache.computeIfAbsent(datasetId, k -> new ArrayList<>());
        for (GoldenQuery gq : goldenQueries) {
            if (gq.getId() == null) {
                gq.setId(idGenerator.getAndIncrement());
            }
            gq.setDatasetId(datasetId);
            if (gq.getCreatedAt() == null) {
                gq.setCreatedAt(LocalDateTime.now());
            }
            dataset.add(gq);
        }

        log.info("Batch added {} golden queries to dataset [{}]", goldenQueries.size(), datasetId);
    }

    /**
     * 获取数据集的统计信息。
     *
     * @param datasetId 数据集 ID
     * @return 统计信息字符串
     */
    public String getDatasetStats(String datasetId) {
        List<GoldenQuery> queries = datasetCache.getOrDefault(datasetId, List.of());
        if (queries.isEmpty()) {
            return "Dataset [" + datasetId + "]: empty";
        }

        Map<String, Long> byDifficulty = queries.stream()
                .filter(q -> q.getDifficulty() != null)
                .collect(Collectors.groupingBy(GoldenQuery::getDifficulty, Collectors.counting()));

        Map<String, Long> byCategory = queries.stream()
                .filter(q -> q.getCategory() != null)
                .collect(Collectors.groupingBy(GoldenQuery::getCategory, Collectors.counting()));

        return String.format("Dataset [%s]: %d queries, difficulties=%s, categories=%s",
                datasetId, queries.size(), byDifficulty, byCategory);
    }

    /**
     * 列出所有数据集 ID。
     *
     * @return 数据集 ID 集合
     */
    public Set<String> listDatasetIds() {
        return Collections.unmodifiableSet(datasetCache.keySet());
    }

    /**
     * 删除数据集。
     *
     * @param datasetId 数据集 ID
     * @return 是否成功删除
     */
    public boolean deleteDataset(String datasetId) {
        List<GoldenQuery> removed = datasetCache.remove(datasetId);
        if (removed != null) {
            log.info("Deleted dataset [{}] with {} queries", datasetId, removed.size());
            return true;
        }
        return false;
    }

    /**
     * 将相关文档 ID 列表序列化为 JSON 字符串。
     *
     * @param docIds 文档 ID 列表
     * @return JSON 字符串
     */
    public String serializeDocIds(List<String> docIds) {
        try {
            return objectMapper.writeValueAsString(docIds);
        } catch (Exception e) {
            log.error("Failed to serialize docIds: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 从 JSON 字符串反序列化相关文档 ID 列表。
     *
     * @param json JSON 字符串
     * @return 文档 ID 列表
     */
    public List<String> deserializeDocIds(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize docIds: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 初始化示例数据集。
     * 如果数据库中已有数据，则从数据库加载；否则初始化示例数据。
     */
    private void initSampleDataset() {
        String sampleDatasetId = "sample-dataset";

        // 检查数据库中是否已有数据
        Long count = goldenQueryMapper.selectCount(
                new LambdaQueryWrapper<GoldenQuery>()
                        .eq(GoldenQuery::getDatasetId, sampleDatasetId));

        if (count != null && count > 0) {
            // 从数据库加载
            List<GoldenQuery> queries = goldenQueryMapper.selectList(
                    new LambdaQueryWrapper<GoldenQuery>()
                            .eq(GoldenQuery::getDatasetId, sampleDatasetId));
            datasetCache.put(sampleDatasetId, queries);
            log.info("Loaded {} golden queries from database for dataset [{}]",
                    queries.size(), sampleDatasetId);
            return;
        }

        // 初始化示例数据
        addGoldenQuery(sampleDatasetId,
                "什么是 RAG（检索增强生成）？",
                "RAG 是一种结合检索和生成的 AI 技术，通过从知识库中检索相关信息来增强大语言模型的回答质量。",
                serializeDocIds(List.of("doc_001", "doc_002")),
                "easy",
                "AI基础");

        addGoldenQuery(sampleDatasetId,
                "如何评估 RAG 系统的检索质量？",
                "可以使用 Recall@K、Precision@K、MRR、NDCG 等指标来评估检索质量。",
                serializeDocIds(List.of("doc_003", "doc_004")),
                "medium",
                "评估方法");

        addGoldenQuery(sampleDatasetId,
                "RRF 融合算法的原理是什么？",
                "Reciprocal Rank Fusion 通过计算每个文档在各排名列表中的倒数排名之和来进行融合排序。",
                serializeDocIds(List.of("doc_005")),
                "medium",
                "检索算法");

        log.info("Initialized sample dataset [{}] with {} queries",
                sampleDatasetId, datasetCache.get(sampleDatasetId).size());
    }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
