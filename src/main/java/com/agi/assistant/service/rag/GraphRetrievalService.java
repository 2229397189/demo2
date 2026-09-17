package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.DocumentChunk;
import com.agi.assistant.model.entity.GraphEntity;
import com.agi.assistant.model.entity.GraphRelation;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.neo4j.driver.Session;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 知识图谱检索服务
 * <p>
 * 基于 Neo4j 实现知识图谱检索，支持：
 * <ul>
 *   <li>实体 / 关系抽取（通过 LLM）</li>
 *   <li>基于实体的图搜索</li>
 *   <li>多跳图扩展（默认 2 跳）</li>
 * </ul>
 */
@Slf4j
@Lazy
@Service
public class GraphRetrievalService {

    /** 默认多跳扩展深度 */
    private static final int DEFAULT_HOP_COUNT = 2;

    /** 每跳最大扩展实体数 */
    private static final int MAX_ENTITIES_PER_HOP = 10;

    /** 单次实体抽取调用的最大字符数：把多个块聚成一批，减少 LLM 调用次数 */
    private static final int EXTRACTION_BATCH_CHARS = 3000;

    /** 实体名短于该长度不建立 MENTIONS 边，避免「它」「此」这类词造成边爆炸 */
    private static final int MIN_MENTION_ENTITY_LENGTH = 2;

    /** 单文档 MENTIONS 边上限，防止异常抽取把图撑爆 */
    private static final int MAX_MENTIONS_PER_DOCUMENT = 2000;

    /** 实体抽取提示词 */
    private static final String ENTITY_EXTRACTION_PROMPT =
            "请从以下文本中抽取所有实体（人名、组织、概念、工具、技术等）和它们之间的关系。\n" +
            "以 JSON 格式输出，格式如下：\n" +
            "{\"entities\": [{\"name\": \"实体名\", \"type\": \"实体类型\"}], " +
            "\"relations\": [{\"source\": \"源实体\", \"target\": \"目标实体\", \"type\": \"关系类型\"}]}\n" +
            "文本内容：\n";

    private final Driver neo4jDriver;
    private final WebClient llmWebClient;
    private final ObjectMapper objectMapper;
    private final OpenAIConfig openAIConfig;

    /** 文档入库时是否构建知识图谱（每篇文档会触发若干次 LLM 实体抽取调用） */
    @Value("${rag.graph-extraction.enabled:true}")
    private boolean graphExtractionEnabled;

    /** 单篇文档参与图谱构建的块数上限，控制 LLM 成本 */
    @Value("${rag.graph-extraction.max-chunks:40}")
    private int graphExtractionMaxChunks;

    public GraphRetrievalService(@Nullable Driver neo4jDriver, OpenAIConfig openAIConfig) {
        this.neo4jDriver = neo4jDriver;
        this.openAIConfig = openAIConfig;
        this.objectMapper = new ObjectMapper();
        this.llmWebClient = WebClient.builder()
                .baseUrl(openAIConfig.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + openAIConfig.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ──────────────────────────────────────────────────────────────
    //  实体 / 关系抽取
    // ──────────────────────────────────────────────────────────────

    /**
     * 使用 LLM 从文本中抽取实体和关系。
     *
     * @param text 待抽取的文本
     * @return 包含 entities 和 relations 的 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return Map.of("entities", Collections.emptyList(), "relations", Collections.emptyList());
        }

        try {
            String prompt = ENTITY_EXTRACTION_PROMPT + text;

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAIConfig.getModel());
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 2000);
            // 关闭思维链：实体抽取是结构化输出任务，思维链会把 max_tokens 吃光导致 content 为空
            openAIConfig.applyThinking(requestBody);

            String responseStr = llmWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseStr == null) {
                log.warn("LLM returned null response for entity extraction");
                return Map.of("entities", Collections.emptyList(), "relations", Collections.emptyList());
            }

            // 从 LLM 响应中提取 JSON
            Map<String, Object> response = objectMapper.readValue(responseStr, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Map.of("entities", Collections.emptyList(), "relations", Collections.emptyList());
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");

            // 提取 JSON 块
            String jsonStr = extractJsonFromContent(content);
            if (jsonStr == null) {
                log.warn("Failed to extract JSON from LLM response: {}", content);
                return Map.of("entities", Collections.emptyList(), "relations", Collections.emptyList());
            }

            Map<String, Object> extracted = objectMapper.readValue(jsonStr, Map.class);
            log.debug("Extracted {} entities and {} relations from text",
                    ((List<?>) extracted.getOrDefault("entities", Collections.emptyList())).size(),
                    ((List<?>) extracted.getOrDefault("relations", Collections.emptyList())).size());

            return extracted;

        } catch (Exception e) {
            log.error("Entity extraction failed: {}", e.getMessage(), e);
            return Map.of("entities", Collections.emptyList(), "relations", Collections.emptyList());
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  图检索
    // ──────────────────────────────────────────────────────────────

    /**
     * 基于实体名称在知识图谱中检索相关文档。
     * <p>
     * 查找包含指定实体的节点及其关联的文档块。
     *
     * @param entityNames 实体名称列表
     * @param topK        最大返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> graphSearch(List<String> entityNames, int topK) {
        if (entityNames == null || entityNames.isEmpty() || neo4jDriver == null) {
            return Collections.emptyList();
        }

        List<SearchResult> allResults = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            // 实体 -> 文档块 的边统一为 MENTIONS（由 writeGraph 建立）。
            // 旧版本只写 Entity 节点、从不创建 DocumentChunk 节点，
            // 所以这条查询此前永远返回空集。
            String cypher =
                    "MATCH (e:Entity)-[:MENTIONS]-(c:DocumentChunk)\n" +
                    "WHERE e.name IN $names\n" +
                    "RETURN DISTINCT c.document_id AS documentId, " +
                    "c.chunk_index AS chunkIndex, " +
                    "c.content AS content, " +
                    "e.name AS entityName\n" +
                    "LIMIT $limit";

            Map<String, Object> params = Map.of("names", entityNames, "limit", topK);

            Result result = session.run(cypher, params);

            while (result.hasNext()) {
                Record record = result.next();
                String documentId = record.get("documentId").asString(null);
                String content = record.get("content").asString(null);
                if (documentId == null || content == null) {
                    continue;
                }
                SearchResult searchResult = SearchResult.builder()
                        .documentId(documentId)
                        .chunkIndex(record.get("chunkIndex").asInt(0))
                        .content(content)
                        .score(1.0)  // 图检索默认分数
                        .source("graph")
                        .metadata(Map.of("entity", String.valueOf(record.get("entityName").asString(null))))
                        .build();
                allResults.add(searchResult);
            }

            log.debug("Graph search returned {} results for entities: {}", allResults.size(), entityNames);

        } catch (Exception e) {
            log.error("Graph search failed: {}", e.getMessage(), e);
        }

        return allResults;
    }

    /**
     * 多跳图扩展检索。
     * <p>
     * 从初始实体出发，沿关系进行最多 hopCount 跳的扩展，
     * 收集沿途的实体和关联文档块。
     *
     * @param startEntities 起始实体列表
     * @param hopCount      最大跳数（默认 2）
     * @param topK          最大返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> multiHopExpand(List<String> startEntities, int hopCount, int topK) {
        if (startEntities == null || startEntities.isEmpty() || neo4jDriver == null) {
            return Collections.emptyList();
        }

        hopCount = Math.max(1, Math.min(hopCount, DEFAULT_HOP_COUNT));

        Set<String> visitedEntities = new HashSet<>(startEntities);
        List<String> currentFrontier = new ArrayList<>(startEntities);

        // 用 LinkedHashMap 去重：同一 (documentId, chunkIndex) 在多跳中可能被多次命中，
        // 旧实现直接往 ArrayList 里堆，导致重复结果把 topK 挤满。
        Map<String, SearchResult> collected = new LinkedHashMap<>();

        try (Session session = neo4jDriver.session()) {
            for (int hop = 0; hop < hopCount && !currentFrontier.isEmpty(); hop++) {
                double score = 1.0 / (hop + 1);  // 跳数越远分数越低

                // (a) 收集当前边界实体「直接提及」的文档块
                String chunkCypher =
                        "MATCH (e:Entity)-[:MENTIONS]->(c:DocumentChunk)\n" +
                        "WHERE e.name IN $names\n" +
                        "RETURN DISTINCT c.document_id AS documentId, " +
                        "c.chunk_index AS chunkIndex, " +
                        "c.content AS content, " +
                        "e.name AS entityName\n" +
                        "LIMIT $limit";

                Map<String, Object> chunkParams = Map.of(
                        "names", currentFrontier,
                        "limit", MAX_ENTITIES_PER_HOP * currentFrontier.size() * 5
                );

                Result chunkResult = session.run(chunkCypher, chunkParams);
                while (chunkResult.hasNext()) {
                    Record record = chunkResult.next();
                    String documentId = record.get("documentId").asString(null);
                    String content = record.get("content").asString(null);
                    if (documentId == null || content == null) {
                        continue;
                    }
                    int chunkIndex = record.get("chunkIndex").asInt(0);
                    String key = documentId + ":" + chunkIndex;

                    SearchResult candidate = SearchResult.builder()
                            .documentId(documentId)
                            .chunkIndex(chunkIndex)
                            .content(content)
                            .score(score)
                            .source("graph")
                            .metadata(Map.of(
                                    "hop", hop + 1,
                                    "entity", String.valueOf(record.get("entityName").asString(null))
                            ))
                            .build();

                    collected.merge(key, candidate,
                            (existing, incoming) -> incoming.getScore() > existing.getScore() ? incoming : existing);
                }

                // (b) 仅沿「实体 -> 实体」关系扩展下一跳。
                //     旧实现把 DocumentChunk 邻居也当成实体塞进 frontier，
                //     下一跳再去匹配 e.name 时永远匹配不到，多跳实际只走了 1 跳。
                String expandCypher =
                        "MATCH (e:Entity)-[r]-(n:Entity)\n" +
                        "WHERE e.name IN $names\n" +
                        "RETURN DISTINCT n.name AS neighborName\n" +
                        "LIMIT $limit";

                Map<String, Object> expandParams = Map.of(
                        "names", currentFrontier,
                        "limit", MAX_ENTITIES_PER_HOP * currentFrontier.size()
                );

                Result expandResult = session.run(expandCypher, expandParams);
                List<String> nextFrontier = new ArrayList<>();
                while (expandResult.hasNext()) {
                    String neighborName = expandResult.next().get("neighborName").asString(null);
                    if (neighborName != null && !neighborName.isBlank()
                            && visitedEntities.add(neighborName)) {
                        nextFrontier.add(neighborName);
                    }
                }

                currentFrontier = nextFrontier;
                log.debug("Hop {}: discovered {} new entities, collected {} chunks",
                        hop + 1, nextFrontier.size(), collected.size());
            }

        } catch (Exception e) {
            log.error("Multi-hop expansion failed: {}", e.getMessage(), e);
        }

        // 按分数排序并截断
        List<SearchResult> allResults = new ArrayList<>(collected.values());
        allResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return allResults.size() > topK ? allResults.subList(0, topK) : allResults;
    }

    /**
     * 图检索主入口：抽取实体 -> 图搜索 -> 多跳扩展。
     *
     * @param query 查询文本
     * @param topK  最大返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> retrieve(String query, int topK) {
        return retrieve(query, DEFAULT_HOP_COUNT, topK);
    }

    /**
     * 图检索主入口。
     *
     * @param query    查询文本
     * @param hopCount 最大跳数
     * @param topK     最大返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> retrieve(String query, int hopCount, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        if (neo4jDriver == null) {
            log.debug("Neo4j is disabled or unavailable, skipping graph retrieval");
            return Collections.emptyList();
        }

        // 1. 从查询中抽取实体
        Map<String, Object> extracted = extractEntities(query);
        List<Map<String, String>> entities = (List<Map<String, String>>) extracted.getOrDefault("entities", Collections.emptyList());

        List<String> entityNames = entities.stream()
                .map(e -> e.get("name"))
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toList());

        if (entityNames.isEmpty()) {
            log.debug("No entities extracted from query: {}", query);
            return Collections.emptyList();
        }

        // 2. 直接图搜索
        List<SearchResult> directResults = graphSearch(entityNames, topK);

        // 3. 多跳扩展
        List<SearchResult> expandedResults = multiHopExpand(entityNames, hopCount, topK);

        // 4. 合并去重
        return mergeAndDedupe(directResults, expandedResults, topK);
    }

    // ──────────────────────────────────────────────────────────────
    //  图谱写入
    // ──────────────────────────────────────────────────────────────

    /** Neo4j 是否可用（未配置时 driver 为 null）。 */
    public boolean isAvailable() {
        return neo4jDriver != null;
    }

    /** 文档入库时的图谱构建开关是否打开。 */
    public boolean isExtractionEnabled() {
        return graphExtractionEnabled;
    }

    /**
     * 文档入库流水线的图谱构建入口：抽取实体/关系 + 建 Entity/DocumentChunk 节点 + MENTIONS 边。
     * <p>
     * 这是此前完全缺失的一环 —— writeGraph 从来没有被任何代码调用过，
     * 而 graphSearch 却在查 {@code (e:Entity)-[r]-(c:DocumentChunk)}，
     * 于是图谱检索永远返回空集。
     * <p>
     * 失败时抛异常，由调用方决定上报 PARTIAL 还是 FAILED（不再静默吞掉）。
     *
     * @param documentId 文档 ID（字符串形式）
     * @param chunks     已分块的文档内容
     * @return 实际写入的 MENTIONS 边数量
     */
    public int buildGraph(String documentId, List<DocumentChunk> chunks) {
        if (neo4jDriver == null) {
            throw new IllegalStateException("Neo4j 不可用，无法构建知识图谱");
        }
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }

        List<DocumentChunk> limited = chunks.size() > graphExtractionMaxChunks
                ? new ArrayList<>(chunks.subList(0, graphExtractionMaxChunks))
                : chunks;

        // 合并抽取结果（跨批次去重）
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        Set<String> seenEntityNames = new HashSet<>();
        Set<String> seenRelationKeys = new HashSet<>();

        for (List<DocumentChunk> batch : batchChunks(limited, EXTRACTION_BATCH_CHARS)) {
            StringBuilder text = new StringBuilder();
            for (DocumentChunk c : batch) {
                text.append(c.getContent() == null ? "" : c.getContent()).append('\n');
            }
            Map<String, Object> extracted = extractEntities(text.toString());
            mergeExtraction(extracted, entities, relations, seenEntityNames, seenRelationKeys);
        }

        int mentions = persistGraph(documentId, entities, relations, limited);
        log.info("Graph built for document [{}]: {} entities, {} relations, {} mention edges ({} chunks scanned)",
                documentId, entities.size(), relations.size(), mentions, limited.size());
        return mentions;
    }

    /**
     * 将实体和关系写入知识图谱（不含文档块节点，保留给外部调用方）。
     *
     * @param entities  实体列表
     * @param relations 关系列表
     * @param documentId 关联文档 ID
     */
    public void writeGraph(List<GraphEntity> entities, List<GraphRelation> relations, String documentId) {
        if (neo4jDriver == null) {
            throw new IllegalStateException("Neo4j not available, cannot write graph");
        }
        if ((entities == null || entities.isEmpty()) && (relations == null || relations.isEmpty())) {
            return;
        }

        List<Map<String, Object>> rawEntities = new ArrayList<>();
        if (entities != null) {
            for (GraphEntity entity : entities) {
                if (entity == null || entity.getName() == null || entity.getName().isBlank()) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("name", entity.getName());
                m.put("type", entity.getType());
                rawEntities.add(m);
            }
        }

        List<Map<String, Object>> rawRelations = new ArrayList<>();
        if (relations != null) {
            for (GraphRelation relation : relations) {
                if (relation == null || relation.getStartEntity() == null || relation.getEndEntity() == null) {
                    continue;
                }
                Map<String, Object> m = new HashMap<>();
                m.put("source", relation.getStartEntity());
                m.put("target", relation.getEndEntity());
                m.put("type", relation.getType());
                rawRelations.add(m);
            }
        }

        persistGraph(documentId, rawEntities, rawRelations, Collections.emptyList());
    }

    // ──────────────────────────────────────────────────────────────
    //  图谱写入内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 把块列表按累计字符数聚成批，控制单次抽取的 prompt 长度。
     */
    private List<List<DocumentChunk>> batchChunks(List<DocumentChunk> chunks, int maxChars) {
        List<List<DocumentChunk>> batches = new ArrayList<>();
        List<DocumentChunk> current = new ArrayList<>();
        int currentChars = 0;

        for (DocumentChunk chunk : chunks) {
            int len = chunk.getContent() == null ? 0 : chunk.getContent().length();
            if (!current.isEmpty() && currentChars + len > maxChars) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(chunk);
            currentChars += len;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    /**
     * 合并一次抽取结果到全局集合，按名称/三元组去重。
     */
    @SuppressWarnings("unchecked")
    private void mergeExtraction(Map<String, Object> extracted,
                                 List<Map<String, Object>> entities,
                                 List<Map<String, Object>> relations,
                                 Set<String> seenEntityNames,
                                 Set<String> seenRelationKeys) {
        if (extracted == null) {
            return;
        }

        Object rawEntities = extracted.get("entities");
        if (rawEntities instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> e = (Map<String, Object>) item;
                String name = str(e.get("name"));
                if (name == null || !seenEntityNames.add(name)) {
                    continue;
                }
                Map<String, Object> clean = new HashMap<>();
                clean.put("name", name);
                clean.put("type", str(e.get("type")));
                entities.add(clean);
            }
        }

        Object rawRelations = extracted.get("relations");
        if (rawRelations instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> r = (Map<String, Object>) item;
                String source = str(r.get("source"));
                String target = str(r.get("target"));
                if (source == null || target == null) {
                    continue;
                }
                String type = str(r.get("type"));
                String key = source + "|" + type + "|" + target;
                if (!seenRelationKeys.add(key)) {
                    continue;
                }
                Map<String, Object> clean = new HashMap<>();
                clean.put("source", source);
                clean.put("target", target);
                clean.put("type", type);
                relations.add(clean);
            }
        }
    }

    /**
     * 在单个写事务中落库：Entity 节点、DocumentChunk 节点、MENTIONS 边、实体关系边。
     *
     * @return 写入的 MENTIONS 边数量
     */
    private int persistGraph(String documentId,
                             List<Map<String, Object>> entities,
                             List<Map<String, Object>> relations,
                             List<DocumentChunk> chunks) {
        AtomicInteger mentionCount = new AtomicInteger(0);

        try (Session session = neo4jDriver.session()) {
            // 一个文档一个写事务，避免 N 条语句 = N 个事务
            session.executeWrite(tx -> {
                // 1. 实体节点
                for (Map<String, Object> e : entities) {
                    tx.run("MERGE (e:Entity {name: $name}) " +
                           "SET e.type = $type, e.document_id = $documentId",
                            Map.of("name", e.get("name"),
                                   "type", e.get("type") != null ? e.get("type") : "Unknown",
                                   "documentId", documentId));
                }

                // 2. 文档块节点（graphSearch / multiHopExpand 依赖这里的 document_id / chunk_index / content）
                for (DocumentChunk chunk : chunks) {
                    int idx = chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0;
                    tx.run("MERGE (c:DocumentChunk {document_id: $documentId, chunk_index: $chunkIndex}) " +
                           "SET c.content = $content",
                            Map.of("documentId", documentId,
                                   "chunkIndex", (long) idx,
                                   "content", chunk.getContent() != null ? chunk.getContent() : ""));
                }

                // 3. MENTIONS 边：靠子串命中把实体挂到它实际出现的块上
                //    （抽取是分批做的，无法直接从 LLM 输出还原「哪个实体出现在哪个块」）
                boolean budgetLeft = true;
                for (Map<String, Object> e : entities) {
                    String name = (String) e.get("name");
                    if (name == null || name.length() < MIN_MENTION_ENTITY_LENGTH) {
                        continue;
                    }
                    for (DocumentChunk chunk : chunks) {
                        if (mentionCount.get() >= MAX_MENTIONS_PER_DOCUMENT) {
                            budgetLeft = false;
                            break;
                        }
                        String content = chunk.getContent();
                        if (content == null || !content.contains(name)) {
                            continue;
                        }
                        int idx = chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0;
                        tx.run("MATCH (e:Entity {name: $name}) " +
                               "MATCH (c:DocumentChunk {document_id: $documentId, chunk_index: $chunkIndex}) " +
                               "MERGE (e)-[m:MENTIONS]->(c) " +
                               "SET m.document_id = $documentId",
                                Map.of("name", name,
                                       "documentId", documentId,
                                       "chunkIndex", (long) idx));
                        mentionCount.incrementAndGet();
                    }
                    if (!budgetLeft) {
                        log.warn("MENTIONS 边达到上限 {}，文档 [{}] 剩余实体跳过建边",
                                MAX_MENTIONS_PER_DOCUMENT, documentId);
                        break;
                    }
                }

                // 4. 实体关系边。用 MERGE 建端点，保证关系里出现但未列入 entities 的实体也能落库
                //    （旧实现用 MATCH，端点缺失时关系被静默丢弃）
                for (Map<String, Object> r : relations) {
                    tx.run("MERGE (a:Entity {name: $source}) " +
                           "MERGE (b:Entity {name: $target}) " +
                           "MERGE (a)-[rel:" + sanitizeRelationType((String) r.get("type")) + "]->(b) " +
                           "SET rel.document_id = $documentId",
                            Map.of("source", r.get("source"),
                                   "target", r.get("target"),
                                   "documentId", documentId));
                }

                return null;
            });

        } catch (Exception e) {
            log.error("Failed to persist graph for document [{}]: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("Failed to write to knowledge graph", e);
        }

        return mentionCount.get();
    }

    /**
     * 取字符串值，空串归一为 null。
     */
    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 从 LLM 返回的文本中提取 JSON 对象字符串。
     */
    private String extractJsonFromContent(String content) {
        if (content == null) {
            return null;
        }

        // 尝试提取 ```json ... ``` 块
        int jsonStart = content.indexOf("```json");
        if (jsonStart >= 0) {
            int jsonEnd = content.indexOf("```", jsonStart + 7);
            if (jsonEnd > jsonStart) {
                return content.substring(jsonStart + 7, jsonEnd).strip();
            }
        }

        // 尝试提取 { ... } 块
        int braceStart = content.indexOf('{');
        int braceEnd = content.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return content.substring(braceStart, braceEnd + 1);
        }

        return null;
    }

    /**
     * 合并并去重检索结果。
     */
    private List<SearchResult> mergeAndDedupe(List<SearchResult> a, List<SearchResult> b, int topK) {
        Map<String, SearchResult> seen = new HashMap<>();

        for (SearchResult result : a) {
            String key = result.getDocumentId() + ":" + result.getChunkIndex();
            seen.putIfAbsent(key, result);
        }

        for (SearchResult result : b) {
            String key = result.getDocumentId() + ":" + result.getChunkIndex();
            seen.merge(key, result, (existing, incoming) -> {
                if (incoming.getScore() > existing.getScore()) {
                    return incoming;
                }
                return existing;
            });
        }

        List<SearchResult> merged = new ArrayList<>(seen.values());
        merged.sort((x, y) -> Double.compare(y.getScore(), x.getScore()));
        return merged.size() > topK ? merged.subList(0, topK) : merged;
    }

    /**
     * 清洗关系类型字符串，使其可用于 Cypher 关系类型标签。
     * 只保留字母数字和下划线，转换为大写；数字开头补前缀，全空则回落 RELATED_TO。
     */
    private String sanitizeRelationType(String type) {
        if (type == null || type.isBlank()) {
            return "RELATED_TO";
        }
        String sanitized = type.replaceAll("[^a-zA-Z0-9_\\s]", "")
                .replaceAll("\\s+", "_")
                .toUpperCase();
        if (sanitized.isBlank()) {
            return "RELATED_TO";
        }
        // Cypher 关系类型不能以数字开头
        return Character.isDigit(sanitized.charAt(0)) ? "R_" + sanitized : sanitized;
    }
}
