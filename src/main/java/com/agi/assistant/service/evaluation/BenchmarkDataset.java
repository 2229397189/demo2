package com.agi.assistant.service.evaluation;

import com.agi.assistant.mapper.DocumentChunkMapper;
import com.agi.assistant.mapper.DocumentMapper;
import com.agi.assistant.mapper.GoldenQueryMapper;
import com.agi.assistant.model.entity.Document;
import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.GoldenQuery;
import com.agi.assistant.model.enums.DocumentStatus;
import com.agi.assistant.service.llm.ModelProviderRouter;
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

    /** expectedAnswer 取自首 chunk 时的最大长度（超出即截断并加省略号，肉眼可辨）。 */
    static final int EXPECTED_ANSWER_MAX_LEN = 500;

    /** 送入 LLM 用于生成问题的来源文本（标题 + 首 chunk）的最大长度。 */
    private static final int QUERY_SOURCE_MAX_LEN = 500;

    /** 生成 golden query 问题时的最大 token 数。 */
    private static final int QUERY_GEN_MAX_TOKENS = 128;

    /** 生成 golden query 问题时的采样温度（偏低，追求稳定、贴近真实提问）。 */
    private static final double QUERY_GEN_TEMPERATURE = 0.3;

    private final ObjectMapper objectMapper;
    private final GoldenQueryMapper goldenQueryMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ModelProviderRouter modelProviderRouter;

    /** 数据集缓存：datasetId → GoldenQuery 列表 */
    private final Map<String, List<GoldenQuery>> datasetCache = new ConcurrentHashMap<>();

    public BenchmarkDataset(ObjectMapper objectMapper, GoldenQueryMapper goldenQueryMapper,
                            DocumentMapper documentMapper, DocumentChunkMapper documentChunkMapper,
                            ModelProviderRouter modelProviderRouter) {
        this.objectMapper = objectMapper;
        this.goldenQueryMapper = goldenQueryMapper;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.modelProviderRouter = modelProviderRouter;
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
     * 从已上传文档构建基准数据集（P0 主路径，供 {@code POST /api/evaluation/datasets/build} 使用）。
     * <p>
     * 与 {@link #importFromDocuments(String, int)} 的区别：本方法为每篇文档用 LLM 生成一个
     * 「用户真会提出的问题」作为 golden query（{@code useLlm=true}），并按需回退，且<b>逐条落库</b>。
     * <p>
     * 取文档规则：状态 ∈ { {@link DocumentStatus#COMPLETED}, {@link DocumentStatus#PARTIAL} }
     * （COMPLETED=全链路成功，PARTIAL=分块已落库、部分索引成功，二者均<u>可用</u>于评测；
     * PENDING / PROCESSING / FAILED 一律排除）。最多取 {@code limit} 篇。
     * <p>
     * 每篇文档生成一条 golden query：
     * <ul>
     *   <li>{@code relevantDocIds} = {@code [该文档真实 id]}（真实 id，不是下标、不是随机数）；</li>
     *   <li>{@code expectedAnswer} = 该文档首个 chunk 的文本，超长则截断到
     *       {@value #EXPECTED_ANSWER_MAX_LEN} 字符并加省略号（截断肉眼可辨）；</li>
     *   <li>{@code query} = 当 {@code useLlm=true} 且 LLM 可用时，用 LLM 从「标题 + 首 chunk」
     *       生成一个真实用户问题；否则回退为<b>文档标题</b>（真实、非编造）。</li>
     * </ul>
     * <b>诚信约定</b>：0 篇文档时返回 {@code 0} 且<b>不产生任何样本</b>，绝不伪造占位数据。
     *
     * @param datasetId 目标数据集 ID
     * @param limit     最多使用多少篇文档（{@code <= 0} 视为 0，不导入）
     * @param useLlm    是否用 LLM 生成问题；false 或 LLM 不可用时回退为文档标题
     * @return 实际落库的 golden query 条数
     */
    public int buildFromDocuments(String datasetId, int limit, boolean useLlm) {
        if (datasetId == null || datasetId.isBlank()) {
            log.warn("buildFromDocuments: datasetId 为空，跳过");
            return 0;
        }
        if (limit <= 0) {
            log.warn("buildFromDocuments: limit={} 非法（须为正），按 0 处理，不导入任何样本", limit);
            return 0;
        }

        // 只取可用于评测的文档：COMPLETED（全链路成功）与 PARTIAL（分块已落库、部分索引成功）。
        // 明确排除 PENDING(0) / PROCESSING(1) / FAILED(3)。
        List<Integer> usableStatuses = List.of(
                DocumentStatus.COMPLETED.getCode(),
                DocumentStatus.PARTIAL.getCode());
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .in(Document::getStatus, usableStatuses)
                        .orderByDesc(Document::getCreatedAt)
                        .last("LIMIT " + limit));

        if (docs == null || docs.isEmpty()) {
            log.warn("buildFromDocuments: 数据集 [{}] 没有可用文档（status ∈ {{COMPLETED, PARTIAL}}），不新增样本",
                    datasetId);
            return 0;
        }

        int imported = 0;
        for (Document doc : docs) {
            if (doc == null || doc.getId() == null) {
                log.warn("buildFromDocuments: 跳过 id 为空的文档记录");
                continue;
            }

            // 首个 chunk 作为 expectedAnswer 来源（真实文本，不做任何编造）
            DocumentChunk firstChunk = documentChunkMapper.selectOne(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .eq(DocumentChunk::getDocumentId, doc.getId())
                            .orderByAsc(DocumentChunk::getChunkIndex)
                            .last("LIMIT 1"));
            String firstChunkText = firstChunk != null ? firstChunk.getContent() : null;
            // 无 chunk 时 expectedAnswer 为空串（如实反映「没有正文」），绝不编造内容
            String expectedAnswer = truncate(
                    firstChunkText == null ? "" : firstChunkText, EXPECTED_ANSWER_MAX_LEN);

            String title = doc.getTitle() != null && !doc.getTitle().isBlank()
                    ? doc.getTitle()
                    : ("文档 " + doc.getId());

            String query = buildQuery(title, firstChunkText, useLlm);

            // 复用 addGoldenQuery：内部走 goldenQueryMapper.insert 逐条落库（并回填自增 id）
            addGoldenQuery(datasetId, query, expectedAnswer,
                    serializeDocIds(List.of(String.valueOf(doc.getId()))),
                    "medium", "文档构建");
            imported++;
        }

        log.info("buildFromDocuments: 数据集 [{}] 从 {} 篇文档构建 {} 条 golden query（useLlm={}）",
                datasetId, docs.size(), imported, useLlm);
        return imported;
    }

    /**
     * 生成一条 golden query 的问题文本。
     * <p>
     * {@code useLlm=true} 且 LLM 可用时用 LLM 从「标题 + 首 chunk」生成；
     * 否则（{@code useLlm=false} / LLM 不可用 / 生成失败 / 返回空）一律回退为文档标题 ——
     * 回退值是真实的文档标题，而非编造的假问题。
     *
     * @param title          文档标题（回退值）
     * @param firstChunkText 首个 chunk 文本（可为 null）
     * @param useLlm         是否允许调用 LLM
     * @return 问题文本（LLM 生成或文档标题）
     */
    private String buildQuery(String title, String firstChunkText, boolean useLlm) {
        if (!useLlm) {
            return title;
        }
        if (modelProviderRouter == null || modelProviderRouter.activeProviderName() == null) {
            log.warn("buildFromDocuments: LLM 不可用（无生效 provider），query 回退为文档标题");
            return title;
        }

        String source = "标题：" + title
                + (firstChunkText != null && !firstChunkText.isBlank()
                ? "\n开头内容：" + truncate(firstChunkText, QUERY_SOURCE_MAX_LEN)
                : "");
        String prompt = String.format("""
                下面是一篇知识库文档的标题与开头内容。请站在真实用户的角度，生成一个该用户会
                提出、且仅凭这篇文档内容即可回答的中文问题。只输出问题本身，不要任何前缀、解释或引号。

                ## 文档
                %s

                ## 问题
                """, source);

        try {
            String generated = modelProviderRouter.chat(
                    List.of(
                            Map.of("role", "system",
                                    "content", "你是一个 RAG 基准数据集构造助手，只输出一个问题。"),
                            Map.of("role", "user", "content", prompt)),
                    QUERY_GEN_TEMPERATURE, QUERY_GEN_MAX_TOKENS);
            if (generated != null && !generated.isBlank()) {
                return generated.strip();
            }
            log.warn("buildFromDocuments: LLM 返回空问题，回退为文档标题");
        } catch (Exception e) {
            log.warn("buildFromDocuments: LLM 生成问题失败（{}），回退为文档标题", e.getMessage());
        }
        return title;
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

        // 查询真实文档（最多取 5 篇）。
        // 修复：此前写死 eq(getStatus, 1)，而 1 是 PROCESSING —— 等于专挑「还在
        // 处理中」的文档来建黄金查询（审计发现 D）。改用枚举语义：COMPLETED
        // （全链路成功）与 PARTIAL（分块已落库、部分索引成功）都可用于评测。
        List<Integer> usableStatuses = List.of(
                DocumentStatus.COMPLETED.getCode(),
                DocumentStatus.PARTIAL.getCode());
        List<Document> realDocs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .in(Document::getStatus, usableStatuses)
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
