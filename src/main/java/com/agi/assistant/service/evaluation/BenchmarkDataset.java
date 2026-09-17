package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.DocumentChunkMapper;
import com.agi.assistant.mapper.DocumentMapper;
import com.agi.assistant.mapper.GoldenQueryMapper;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.GoldenQuery;
import com.agi.assistant.model.enums.DocumentStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    /** 数据集缓存：datasetId → GoldenQuery 列表 */
    private final Map<String, List<GoldenQuery>> datasetCache = new ConcurrentHashMap<>();

    public BenchmarkDataset(ObjectMapper objectMapper, GoldenQueryMapper goldenQueryMapper,
                            DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper) {
        this.objectMapper = objectMapper;
        this.goldenQueryMapper = goldenQueryMapper;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
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
        // 不手工设置 id，交给数据库自增（golden_query.id 为 AUTO_INCREMENT），
        // 避免重启后 idGenerator 从 1 自增导致与已有主键冲突而 insert 失败
        goldenQuery.setDatasetId(datasetId);
        goldenQuery.setQuery(query);
        goldenQuery.setExpectedAnswer(expectedAnswer);
        goldenQuery.setRelevantDocIds(relevantDocIds);
        goldenQuery.setDifficulty(difficulty);
        goldenQuery.setCategory(category);
        goldenQuery.setCreatedAt(LocalDateTime.now());

        // 保存到数据库（落库后 MyBatis-Plus 会回填自增 id）
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
     * <p>
     * bug1 修复：原先只写入内存缓存、不落库，重启即丢。
     * 现改为逐条 insert 到数据库，并同步更新缓存。
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
            // 不手工设置 id，清空后交给数据库自增，避免主键冲突
            gq.setId(null);
            gq.setDatasetId(datasetId);
            if (gq.getCreatedAt() == null) {
                gq.setCreatedAt(LocalDateTime.now());
            }
            try {
                goldenQueryMapper.insert(gq);
            } catch (Exception e) {
                log.warn("批量新增中单条落库失败 datasetId=[{}], query='{}': {}",
                        datasetId, truncate(gq.getQuery(), 30), e.getMessage());
            }
            dataset.add(gq);
        }

        log.info("批量新增 {} 条 golden query 到数据集 [{}]（已逐条落库）",
                goldenQueries.size(), datasetId);
    }

    /**
     * 从已上传文档构建数据集（供前端「从文档导入」使用）。
     * <p>
     * 查询已完成（status = {@link DocumentStatus#COMPLETED}，即 2）的文档，
     * 为每篇文档生成一条 golden query：
     * <ul>
     *   <li>query：使用文档标题</li>
     *   <li>expectedAnswer：使用首个 chunk 的内容（截断作为摘要）</li>
     *   <li>relevantDocIds：使用文档真实 id（确保评测时检索指标能正确匹配）</li>
     * </ul>
     *
     * @param datasetId 目标数据集 ID
     * @param limit     最多使用多少篇文档
     * @return 实际导入的 golden query 条数
     */
    public int importFromDocuments(String datasetId, int limit) {
        if (datasetId == null || datasetId.isBlank()) {
            log.warn("importFromDocuments: datasetId 为空，跳过");
            return 0;
        }
        if (limit <= 0) {
            limit = 4;
        }

        // 只取真正处理完成的文档：COMPLETED = 2。
        // 注意 PENDING=0 / PROCESSING=1 / FAILED=3 / PARTIAL=4 都不是「可用于评测」的状态。
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getStatus, DocumentStatus.COMPLETED.getCode())
                        .orderByDesc(Document::getCreatedAt)
                        .last("LIMIT " + limit));

        if (docs.isEmpty()) {
            log.warn("没有已完成(status=1)的文档可用于构建数据集 [{}]", datasetId);
            return 0;
        }

        int count = 0;
        for (Document doc : docs) {
            // 取该文档首个 chunk 作为 expectedAnswer（即文档摘要文本）
            DocumentChunk firstChunk = documentChunkMapper.selectOne(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .eq(DocumentChunk::getDocumentId, doc.getId())
                            .orderByAsc(DocumentChunk::getChunkIndex)
                            .last("LIMIT 1"));

            String expectedAnswer = firstChunk != null
                    ? truncate(firstChunk.getContent(), 1000)
                    : "";
            String query = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle()
                    : ("文档 " + doc.getId());

            addGoldenQuery(datasetId, query, expectedAnswer,
                    serializeDocIds(List.of(String.valueOf(doc.getId()))),
                    "medium", "文档导入");
            count++;
        }

        log.info("从文档构建数据集 [{}] 完成，新增 {} 条 golden query", datasetId, count);
        return count;
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
     * 使用数据库中已上传的真实文档 ID 来构建黄金查询，
     * 确保评测时检索指标的文档 ID 能正确匹配。
     */
    private void initSampleDataset() {
        String sampleDatasetId = "sample-dataset";

        // 检查数据库中是否已有数据
        Long count = goldenQueryMapper.selectCount(
                new LambdaQueryWrapper<GoldenQuery>()
                        .eq(GoldenQuery::getDatasetId, sampleDatasetId));

        if (count != null && count > 0) {
            // 检查已有数据是否使用假的 doc_ 前缀 ID
            List<GoldenQuery> existing = goldenQueryMapper.selectList(
                    new LambdaQueryWrapper<GoldenQuery>()
                            .eq(GoldenQuery::getDatasetId, sampleDatasetId));
            boolean hasFakeIds = existing.stream()
                    .anyMatch(gq -> gq.getRelevantDocIds() != null
                            && gq.getRelevantDocIds().contains("doc_"));

            if (!hasFakeIds) {
                // 数据已是真实 ID，直接加载到缓存
                datasetCache.put(sampleDatasetId, existing);
                log.info("Loaded {} golden queries from database for dataset [{}]",
                        existing.size(), sampleDatasetId);
                return;
            }

            // 旧数据使用假的 doc_ ID，删除并重建
            log.info("Sample dataset has fake doc_ IDs, refreshing with real document IDs...");
            goldenQueryMapper.delete(
                    new LambdaQueryWrapper<GoldenQuery>()
                            .eq(GoldenQuery::getDatasetId, sampleDatasetId));
        }

        // 查询真实文档（最多取 5 篇）
        List<Document> realDocs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getStatus, 1)
                        .last("LIMIT 5"));

        if (realDocs.isEmpty()) {
            // 没有已上传的文档，仍用占位 ID 创建（等用户上传文档后重启即可）
            log.warn("No uploaded documents found. Sample dataset will use placeholder IDs. "
                    + "Upload documents and restart to get meaningful evaluation metrics.");
            addGoldenQuery(sampleDatasetId,
                    "什么是 RAG（检索增强生成）？",
                    "RAG 是一种结合检索和生成的 AI 技术，通过从知识库中检索相关信息来增强大语言模型的回答质量。",
                    serializeDocIds(List.of("doc_001", "doc_002")),
                    "easy", "AI基础");
            addGoldenQuery(sampleDatasetId,
                    "如何评估 RAG 系统的检索质量？",
                    "可以使用 Recall@K、Precision@K、MRR、NDCG 等指标来评估检索质量。",
                    serializeDocIds(List.of("doc_003", "doc_004")),
                    "medium", "评估方法");
            addGoldenQuery(sampleDatasetId,
                    "RRF 融合算法的原理是什么？",
                    "Reciprocal Rank Fusion 通过计算每个文档在各排名列表中的倒数排名之和来进行融合排序。",
                    serializeDocIds(List.of("doc_005")),
                    "medium", "检索算法");
            return;
        }

        // 用真实文档 ID 创建黄金查询
        List<String> realDocIds = realDocs.stream()
                .map(d -> String.valueOf(d.getId()))
                .collect(Collectors.toList());

        log.info("Creating sample dataset with {} real document IDs: {}",
                realDocIds.size(), realDocIds);

        // 根据文档数量分配 relevantDocIds
        List<String> firstBatch = realDocIds.subList(0, Math.min(2, realDocIds.size()));
        List<String> secondBatch = realDocIds.size() >= 4
                ? realDocIds.subList(2, 4)
                : realDocIds.subList(0, Math.min(2, realDocIds.size()));
        List<String> thirdBatch = realDocIds.size() >= 5
                ? realDocIds.subList(4, 5)
                : realDocIds.subList(0, Math.min(1, realDocIds.size()));

        addGoldenQuery(sampleDatasetId,
                "什么是 RAG（检索增强生成）？",
                "RAG 是一种结合检索和生成的 AI 技术，通过从知识库中检索相关信息来增强大语言模型的回答质量。",
                serializeDocIds(firstBatch),
                "easy", "AI基础");

        addGoldenQuery(sampleDatasetId,
                "如何评估 RAG 系统的检索质量？",
                "可以使用 Recall@K、Precision@K、MRR、NDCG 等指标来评估检索质量。",
                serializeDocIds(secondBatch),
                "medium", "评估方法");

        addGoldenQuery(sampleDatasetId,
                "RRF 融合算法的原理是什么？",
                "Reciprocal Rank Fusion 通过计算每个文档在各排名列表中的倒数排名之和来进行融合排序。",
                serializeDocIds(thirdBatch),
                "medium", "检索算法");

        log.info("Initialized sample dataset [{}] with {} queries using real doc IDs",
                sampleDatasetId, datasetCache.get(sampleDatasetId).size());
    }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
