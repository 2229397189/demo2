package com.agi.assistant.service.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.ExistsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import com.agi.assistant.model.entity.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25 keyword retrieval service.
 * <p>
 * Implements BM25 keyword retrieval based on Elasticsearch,
 * supporting Chinese IK analyzer.
 * Provides index creation, document indexing, search, and deletion operations
 * with field weight configuration.
 */
@Slf4j
@Lazy
@Service
public class BM25Service {

    /** Default index name */
    private static final String DEFAULT_INDEX = "rag_documents";

    /** Batch size for bulk indexing */
    private static final int BULK_BATCH_SIZE = 100;

    private final ElasticsearchClient esClient;

    /**
     * 索引期 / 检索期分词器，来自 rag.analyzer.index / rag.analyzer.search。
     * <p>
     * 默认 standard（ES 内置，零依赖）。装了 analysis-ik 插件后改成
     * ik_max_word（索引）/ ik_smart（检索）即可，无需改代码。
     * <p>
     * 注意：分词器定义在字段 mapping 里，已存在的索引不会因为配置变更而改变，
     * 需要先删除索引（或重建）才会生效。
     */
    private final String indexAnalyzer;
    private final String searchAnalyzer;

    public BM25Service(ElasticsearchClient esClient,
                       @Value("${rag.analyzer.index:standard}") String indexAnalyzer,
                       @Value("${rag.analyzer.search:standard}") String searchAnalyzer) {
        this.esClient = esClient;
        this.indexAnalyzer = (indexAnalyzer == null || indexAnalyzer.isBlank()) ? "standard" : indexAnalyzer;
        this.searchAnalyzer = (searchAnalyzer == null || searchAnalyzer.isBlank()) ? "standard" : searchAnalyzer;
    }

    /**
     * 探测 Elasticsearch 是否可用。
     * <p>
     * 之前 BM25Service 没有可用性探测，而 indexDocument 内部吞掉异常只打 warn，
     * 导致上层无法区分「ES 没开」和「写入失败」，只能无条件上报成功。
     */
    public boolean isAvailable() {
        try {
            esClient.ping();
            return true;
        } catch (Exception e) {
            log.debug("Elasticsearch ping failed: {}", e.getMessage());
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Index Management
    // ──────────────────────────────────────────────────────────────

    /**
     * Create Elasticsearch index with default name.
     * Skips if index already exists.
     */
    public void createIndex() {
        createIndex(DEFAULT_INDEX);
    }

    /**
     * Create Elasticsearch index with IK Chinese analyzer.
     * Defines title, content, tags, document_id, chunk_index fields.
     *
     * @param indexName index name
     */
    public void createIndex(String indexName) {
        try {
            // Check if index already exists
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();

            if (exists) {
                log.info("Index [{}] already exists, skipping creation. " +
                         "Active analyzer config: index={}, search={}. " +
                         "改变分词器需要先删除索引再重建（mapping 不会就地更新）。",
                        indexName, indexAnalyzer, searchAnalyzer);
                return;
            }

            log.info("Creating Elasticsearch index [{}] with analyzer: index={}, search={}",
                    indexName, indexAnalyzer, searchAnalyzer);

            CreateIndexRequest request = CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                    )
                    .mappings(m -> m
                            .properties("document_id", p -> p.keyword(k -> k))
                            .properties("chunk_index", p -> p.integer(i -> i))
                            .properties("title", p -> p.text(t -> t
                                    .analyzer(indexAnalyzer)
                                    .searchAnalyzer(searchAnalyzer)
                            ))
                            .properties("content", p -> p.text(t -> t
                                    .analyzer(indexAnalyzer)
                                    .searchAnalyzer(searchAnalyzer)
                            ))
                            .properties("tags", p -> p.text(t -> t
                                    .analyzer(indexAnalyzer)
                                    .searchAnalyzer(searchAnalyzer)
                            ))
                    )
            );

            boolean acknowledged = esClient.indices().create(request).acknowledged();
            if (acknowledged) {
                log.info("Index [{}] created successfully", indexName);
            } else {
                log.error("Index [{}] creation not acknowledged", indexName);
            }

        } catch (Exception e) {
            log.warn("Failed to create index [{}], ES may be unavailable: {}", indexName, e.getMessage());
        }
    }

    /**
     * Delete Elasticsearch index with default name.
     */
    public void deleteIndex() {
        deleteIndex(DEFAULT_INDEX);
    }

    /**
     * Delete Elasticsearch index.
     *
     * @param indexName index name
     */
    public void deleteIndex(String indexName) {
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();

            if (!exists) {
                log.info("Index [{}] does not exist, nothing to delete", indexName);
                return;
            }

            esClient.indices().delete(DeleteIndexRequest.of(d -> d.index(indexName)));
            log.info("Index [{}] deleted", indexName);

        } catch (Exception e) {
            log.warn("Failed to delete index [{}], ES may be unavailable: {}", indexName, e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Document Indexing
    // ──────────────────────────────────────────────────────────────

    /**
     * Index a single document chunk to the default index.
     *
     * @return true 表示确实写入成功；false 表示 ES 不可用或写入失败（调用方据此判断是否需要降级上报）
     */
    public boolean indexDocument(String id, String documentId, int chunkIndex,
                              String title, String content, List<String> tags) {
        return indexDocument(DEFAULT_INDEX, id, documentId, chunkIndex, title, content, tags);
    }

    /**
     * Index a single document chunk to the specified index.
     *
     * @return true 写入成功；false 写入失败（异常已在内部记录，不再吞成「假装成功」）
     */
    public boolean indexDocument(String indexName, String id, String documentId, int chunkIndex,
                              String title, String content, List<String> tags) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("document_id", documentId);
            doc.put("chunk_index", chunkIndex);
            doc.put("title", title != null ? title : "");
            doc.put("content", content != null ? content : "");
            doc.put("tags", tags != null ? String.join(" ", tags) : "");

            esClient.index(IndexRequest.of(i -> i
                    .index(indexName)
                    .id(id)
                    .document(doc)
            ));

            log.debug("Indexed document [{}] to [{}]", id, indexName);
            return true;

        } catch (Exception e) {
            log.warn("Failed to index document [{}] into [{}]: {}", id, indexName, e.getMessage());
            return false;
        }
    }

    /**
     * Batch index document chunks.
     *
     * @return true 全部成功；false 存在失败项或异常
     */
    public boolean indexDocumentsBatch(List<Map<String, Object>> documents) {
        return indexDocumentsBatch(DEFAULT_INDEX, documents);
    }

    /**
     * Batch index document chunks to specified index.
     *
     * @return true 全部成功；false 存在失败项或异常
     */
    public boolean indexDocumentsBatch(String indexName, List<Map<String, Object>> documents) {
        if (documents == null || documents.isEmpty()) {
            return true;
        }

        try {
            boolean allOk = true;
            for (int i = 0; i < documents.size(); i += BULK_BATCH_SIZE) {
                int end = Math.min(i + BULK_BATCH_SIZE, documents.size());
                List<Map<String, Object>> batch = documents.subList(i, end);

                BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
                for (Map<String, Object> doc : batch) {
                    Map<String, Object> source = new HashMap<>();
                    source.put("document_id", String.valueOf(doc.getOrDefault("documentId", "")));
                    source.put("chunk_index", doc.getOrDefault("chunkIndex", 0));
                    source.put("title", String.valueOf(doc.getOrDefault("title", "")));
                    source.put("content", String.valueOf(doc.getOrDefault("content", "")));
                    source.put("tags", String.valueOf(doc.getOrDefault("tags", "")));

                    bulkBuilder.operations(op -> op
                            .index(idx -> idx
                                    .index(indexName)
                                    .id(String.valueOf(doc.get("id")))
                                    .document(source)
                            )
                    );
                }

                BulkResponse response = esClient.bulk(bulkBuilder.build());
                if (response.errors()) {
                    allOk = false;
                    String failures = response.items().stream()
                            .filter(item -> item.error() != null)
                            .map(item -> item.id() + ": " + item.error().reason())
                            .collect(Collectors.joining(", "));
                    log.warn("Bulk index has failures: {}", failures);
                }

                log.debug("Bulk indexed {} documents to [{}]", batch.size(), indexName);
            }

            return allOk;

        } catch (IOException e) {
            log.error("Failed to bulk index documents: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Delete all chunks for a given document ID.
     */
    public void deleteByDocumentId(String documentId) {
        deleteByDocumentId(DEFAULT_INDEX, documentId);
    }

    /**
     * Delete all chunks for a given document ID in the specified index.
     */
    public void deleteByDocumentId(String indexName, String documentId) {
        try {
            esClient.deleteByQuery(DeleteByQueryRequest.of(d -> d
                    .index(indexName)
                    .query(q -> q
                            .term(t -> t
                                    .field("document_id")
                                    .value(documentId)
                            )
                    )
            ));

            log.debug("Deleted chunks for document [{}] from [{}]", documentId, indexName);

        } catch (IOException e) {
            log.error("Failed to delete documents for [{}]: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete documents from Elasticsearch", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Search
    // ──────────────────────────────────────────────────────────────

    /**
     * BM25 keyword search with default index and weights.
     */
    public List<SearchResult> search(String query, int topK) {
        return search(DEFAULT_INDEX, query, null, topK);
    }

    /**
     * BM25 keyword search with default index and custom weights.
     */
    public List<SearchResult> search(String query, Map<String, Float> fieldWeights, int topK) {
        return search(DEFAULT_INDEX, query, fieldWeights, topK);
    }

    /**
     * BM25 keyword search.
     */
    public List<SearchResult> search(String indexName, String query,
                                     Map<String, Float> fieldWeights, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        try {
            // Build field weights
            Map<String, Float> weights = fieldWeights != null ? fieldWeights : getDefaultFieldWeightMap();

            // Build multi_match query
            Query multiMatchQuery = Query.of(q -> q
                    .multiMatch(mm -> {
                        mm.query(query)
                                .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields)
                                .fuzziness("AUTO")
                                .prefixLength(2);
                        for (Map.Entry<String, Float> entry : weights.entrySet()) {
                            mm.fields(entry.getKey() + "^" + entry.getValue());
                        }
                        return mm;
                    })
            );

            // Build bool query: must(multi_match) + filter(exists content)
            Query boolQuery = Query.of(q -> q
                    .bool(BoolQuery.of(b -> b
                            .must(multiMatchQuery)
                            .filter(ExistsQuery.of(e -> e.field("content"))._toQuery())
                    ))
            );

            // Execute search
            SearchResponse<Map> response = esClient.search(SearchRequest.of(s -> s
                            .index(indexName)
                            .query(boolQuery)
                            .size(topK)
                            .highlight(h -> h
                                    .preTags("<em>")
                                    .postTags("</em>")
                                    .fields("content", hf -> hf.fragmentSize(200).numberOfFragments(1))
                                    .fields("title", hf -> hf)
                            )
                    ),
                    Map.class
            );

            return parseSearchResponse(response);

        } catch (Exception e) {
            // Index not found or ES unavailable — degrade gracefully
            log.warn("BM25 search unavailable for query [{}]: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get document count for specified index.
     */
    public long getDocumentCount(String indexName) {
        try {
            CountResponse response = esClient.count(CountRequest.of(c -> c
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m))
            ));
            return response.count();
        } catch (IOException e) {
            log.error("Failed to get document count for [{}]: {}", indexName, e.getMessage(), e);
            return 0;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Internal Methods
    // ──────────────────────────────────────────────────────────────

    /**
     * Get default field weight map.
     */
    private Map<String, Float> getDefaultFieldWeightMap() {
        Map<String, Float> weights = new HashMap<>();
        weights.put("title", 3.0f);
        weights.put("content", 1.0f);
        weights.put("tags", 2.0f);
        return weights;
    }

    /**
     * Parse Elasticsearch search response into SearchResult list.
     */
    private List<SearchResult> parseSearchResponse(SearchResponse<Map> response) {
        List<SearchResult> results = new ArrayList<>();

        TotalHits totalHits = response.hits().total();
        if (totalHits != null && totalHits.value() == 0) {
            return results;
        }

        for (Hit<Map> hit : response.hits().hits()) {
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }

            String documentId = String.valueOf(source.getOrDefault("document_id", ""));
            int chunkIndex = source.containsKey("chunk_index")
                    ? ((Number) source.get("chunk_index")).intValue() : 0;
            String content = String.valueOf(source.getOrDefault("content", ""));
            String title = String.valueOf(source.getOrDefault("title", ""));

            // Use highlight content if available
            if (hit.highlight() != null) {
                if (hit.highlight().containsKey("content")) {
                    String highlighted = String.join("...", hit.highlight().get("content"));
                    if (!highlighted.isEmpty()) {
                        content = highlighted;
                    }
                }
                if (hit.highlight().containsKey("title")) {
                    String highlighted = String.join("...", hit.highlight().get("title"));
                    if (!highlighted.isEmpty()) {
                        title = highlighted;
                    }
                }
            }

            SearchResult result = SearchResult.builder()
                    .documentId(documentId)
                    .chunkIndex(chunkIndex)
                    .content(content)
                    .score(hit.score() != null ? hit.score() : 0.0)
                    .source("sparse")
                    .title(title)
                    .build();

            results.add(result);
        }

        log.debug("BM25 search returned {} results", results.size());
        return results;
    }
}
