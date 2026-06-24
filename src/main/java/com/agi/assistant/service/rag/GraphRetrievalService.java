package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.GraphEntity;
import com.agi.assistant.model.entity.GraphRelation;
import com.agi.assistant.model.entity.SearchResult;
import com.agi.assistant.config.OpenAIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
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

            Map<String, Object> requestBody = Map.of(
                    "model", openAIConfig.getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.1,
                    "max_tokens", 2000
            );

            String responseStr = llmWebClient.post()
                    .uri("/v1/chat/completions")
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
            // 查询实体节点以及通过关系连接到的文档块
            String cypher =
                    "MATCH (e:Entity)-[r*1..2]-(c:DocumentChunk)\n" +
                    "WHERE e.name IN $names\n" +
                    "RETURN DISTINCT c.document_id AS documentId, " +
                    "c.chunk_index AS chunkIndex, " +
                    "c.content AS content, " +
                    "e.name AS entityName, " +
                    "type(relationships(path)[0]) AS relationType\n" +
                    "LIMIT $limit";

            // 使用简化查询代替路径查询
            String simpleCypher =
                    "MATCH (e:Entity)-[r]-(c:DocumentChunk)\n" +
                    "WHERE e.name IN $names\n" +
                    "RETURN DISTINCT c.document_id AS documentId, " +
                    "c.chunk_index AS chunkIndex, " +
                    "c.content AS content, " +
                    "e.name AS entityName\n" +
                    "LIMIT $limit";

            Map<String, Object> params = Map.of("names", entityNames, "limit", topK);

            Result result = session.run(simpleCypher, params);

            while (result.hasNext()) {
                Record record = result.next();
                SearchResult searchResult = SearchResult.builder()
                        .documentId(record.get("documentId").asString())
                        .chunkIndex(record.get("chunkIndex").asInt())
                        .content(record.get("content").asString())
                        .score(1.0)  // 图检索默认分数
                        .source("graph")
                        .metadata(Map.of("entity", record.get("entityName").asString()))
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
        List<SearchResult> allResults = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            for (int hop = 0; hop < hopCount && !currentFrontier.isEmpty(); hop++) {
                List<String> nextFrontier = new ArrayList<>();

                // 查询当前边界实体的邻居实体和关联文档块
                String cypher =
                        "MATCH (e:Entity)-[r]-(neighbor)\n" +
                        "WHERE e.name IN $names\n" +
                        "RETURN e.name AS sourceEntity, " +
                        "type(r) AS relationType, " +
                        "labels(neighbor) AS neighborLabels, " +
                        "neighbor.name AS neighborName, " +
                        "neighbor.document_id AS documentId, " +
                        "neighbor.chunk_index AS chunkIndex, " +
                        "neighbor.content AS content\n" +
                        "LIMIT $limit";

                Map<String, Object> params = Map.of(
                        "names", currentFrontier,
                        "limit", MAX_ENTITIES_PER_HOP * currentFrontier.size()
                );

                Result result = session.run(cypher, params);

                while (result.hasNext()) {
                    Record record = result.next();
                    String neighborName = record.get("neighborName").asString();

                    // 记录新发现的实体
                    if (neighborName != null && !neighborName.isBlank()
                            && !visitedEntities.contains(neighborName)) {
                        visitedEntities.add(neighborName);
                        nextFrontier.add(neighborName);
                    }

                    // 如果邻居是文档块，加入结果
                    if (!record.get("documentId").isNull() && !record.get("content").isNull()) {
                        double score = 1.0 / (hop + 1);  // 跳数越远分数越低
                        SearchResult searchResult = SearchResult.builder()
                                .documentId(record.get("documentId").asString())
                                .chunkIndex(record.get("chunkIndex").asInt())
                                .content(record.get("content").asString())
                                .score(score)
                                .source("graph")
                                .metadata(Map.of(
                                        "hop", hop + 1,
                                        "sourceEntity", record.get("sourceEntity").asString(),
                                        "relationType", record.get("relationType").asString()
                                ))
                                .build();
                        allResults.add(searchResult);
                    }
                }

                currentFrontier = nextFrontier;
                log.debug("Hop {}: discovered {} new entities, total results={}",
                        hop + 1, nextFrontier.size(), allResults.size());
            }

        } catch (Exception e) {
            log.error("Multi-hop expansion failed: {}", e.getMessage(), e);
        }

        // 按分数排序并截断
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

    /**
     * 将实体和关系写入知识图谱。
     *
     * @param entities  实体列表
     * @param relations 关系列表
     * @param documentId 关联文档 ID
     */
    public void writeGraph(List<GraphEntity> entities, List<GraphRelation> relations, String documentId) {
        if (neo4jDriver == null) {
            log.warn("Neo4j not available, skipping graph write");
            return;
        }
        if ((entities == null || entities.isEmpty()) && (relations == null || relations.isEmpty())) {
            return;
        }

        try (Session session = neo4jDriver.session()) {
            // 写入实体节点
            if (entities != null) {
                for (GraphEntity entity : entities) {
                    String cypher =
                            "MERGE (e:Entity {name: $name})\n" +
                            "SET e.type = $type, e.document_id = $documentId\n" +
                            "RETURN e";
                    Map<String, Object> params = Map.of(
                            "name", entity.getName(),
                            "type", entity.getType() != null ? entity.getType() : "Unknown",
                            "documentId", documentId != null ? documentId : ""
                    );
                    session.run(cypher, params);
                }
            }

            // 写入关系
            if (relations != null) {
                for (GraphRelation relation : relations) {
                    String cypher =
                            "MATCH (a:Entity {name: $source})\n" +
                            "MATCH (b:Entity {name: $target})\n" +
                            "MERGE (a)-[r:" + sanitizeRelationType(relation.getType()) + "]->(b)\n" +
                            "SET r.document_id = $documentId\n" +
                            "RETURN type(r)";
                    Map<String, Object> params = Map.of(
                            "source", relation.getStartEntity(),
                            "target", relation.getEndEntity(),
                            "documentId", documentId != null ? documentId : ""
                    );
                    session.run(cypher, params);
                }
            }

            log.debug("Wrote {} entities and {} relations for document [{}]",
                    entities != null ? entities.size() : 0,
                    relations != null ? relations.size() : 0,
                    documentId);

        } catch (Exception e) {
            log.error("Failed to write graph for document [{}]: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("Failed to write to knowledge graph", e);
        }
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
     * 只保留字母数字和下划线，转换为大写。
     */
    private String sanitizeRelationType(String type) {
        if (type == null || type.isBlank()) {
            return "RELATED_TO";
        }
        return type.replaceAll("[^a-zA-Z0-9_\\s]", "")
                .replaceAll("\\s+", "_")
                .toUpperCase();
    }
}
