package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.SearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量数据库服务
 * <p>
 * 提供集合管理、向量插入、向量检索和删除操作。
 * 集合 Schema 包含字段：id(varchar), document_id(varchar), chunk_index(int64), embedding(float_vector[1024])。
 */
@Slf4j
@Service
public class MilvusService {

    private static final String ID_FIELD = "id";
    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String CONTENT_FIELD = "content";
    private static final String EMBEDDING_FIELD = "embedding";

    private static final IndexType INDEX_TYPE = IndexType.IVF_FLAT;
    private static final MetricType METRIC_TYPE = MetricType.COSINE;
    private static final int NLIST = 128;

    private final MilvusServiceClient milvusClient;
    private final String collectionName;
    private final int vectorDim;

    public MilvusService(@org.springframework.lang.Nullable MilvusServiceClient milvusClient,
                         @org.springframework.beans.factory.annotation.Value("${milvus.collection:documents}") String milvusCollectionName,
                         @org.springframework.beans.factory.annotation.Value("${milvus.vector-dim:1024}") Integer milvusVectorDim) {
        this.milvusClient = milvusClient;
        this.collectionName = milvusCollectionName;
        this.vectorDim = milvusVectorDim != null ? milvusVectorDim : 1024;
    }

    // ──────────────────────────────────────────────────────────────
    //  集合管理
    // ──────────────────────────────────────────────────────────────

    /**
     * 创建 Milvus 集合（如果尚不存在）。
     * <p>
     * Schema:
     * - id: VARCHAR (主键)
     * - document_id: VARCHAR
     * - chunk_index: INT64
     * - content: VARCHAR
     * - embedding: FLOAT_VECTOR[vectorDim]
     */
    public void createCollection() {
        if (milvusClient == null) {
            log.warn("Milvus client not available, skipping collection creation");
            return;
        }
        try {
            // 检查集合是否已存在
            R<Boolean> hasResp = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );

            if (hasResp.getData() != null && hasResp.getData()) {
                log.info("Collection [{}] already exists, skipping creation", collectionName);
                ensureIndex();
                loadCollection();
                return;
            }

            log.info("Creating collection [{}] with vector dim={}", collectionName, vectorDim);

            // 定义字段
            FieldType idField = FieldType.newBuilder()
                    .withName(ID_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();

            FieldType documentIdField = FieldType.newBuilder()
                    .withName(DOCUMENT_ID_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .build();

            FieldType chunkIndexField = FieldType.newBuilder()
                    .withName(CHUNK_INDEX_FIELD)
                    .withDataType(DataType.Int64)
                    .build();

            FieldType contentField = FieldType.newBuilder()
                    .withName(CONTENT_FIELD)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build();

            FieldType embeddingField = FieldType.newBuilder()
                    .withName(EMBEDDING_FIELD)
                    .withDataType(DataType.FloatVector)
                    .withDimension(vectorDim)
                    .build();

            // 创建集合
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withShardsNum(2)
                    .addFieldType(idField)
                    .addFieldType(documentIdField)
                    .addFieldType(chunkIndexField)
                    .addFieldType(contentField)
                    .addFieldType(embeddingField)
                    .build();

            R<RpcStatus> createResp =
                    milvusClient.createCollection(createParam);

            if (createResp.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to create collection: " + createResp.getMessage());
            }

            // 创建向量索引
            ensureIndex();

            // 加载集合到内存
            loadCollection();

            log.info("Collection [{}] created successfully", collectionName);

        } catch (Exception e) {
            log.error("Failed to create collection [{}]: {}", collectionName, e.getMessage(), e);
            throw new RuntimeException("Failed to create Milvus collection", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  向量操作
    // ──────────────────────────────────────────────────────────────

    /**
     * 插入向量数据。
     *
     * @param ids          块唯一标识列表
     * @param documentIds  文档 ID 列表
     * @param chunkIndices 块索引列表
     * @param contents     块文本内容列表
     * @param embeddings   嵌入向量列表
     */
    public void insertVectors(List<String> ids,
                              List<String> documentIds,
                              List<Long> chunkIndices,
                              List<String> contents,
                              List<List<Float>> embeddings) {
        if (milvusClient == null) {
            log.warn("Milvus client not available, skipping vector insert");
            return;
        }
        if (ids == null || ids.isEmpty()) {
            return;
        }

        int size = ids.size();
        validateListSize("ids", ids, size);
        validateListSize("documentIds", documentIds, size);
        validateListSize("chunkIndices", chunkIndices, size);
        validateListSize("contents", contents, size);
        validateListSize("embeddings", embeddings, size);

        try {
            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field(ID_FIELD, ids),
                    new InsertParam.Field(DOCUMENT_ID_FIELD, documentIds),
                    new InsertParam.Field(CHUNK_INDEX_FIELD, chunkIndices),
                    new InsertParam.Field(CONTENT_FIELD, contents),
                    new InsertParam.Field(EMBEDDING_FIELD, embeddings)
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            R<io.milvus.grpc.MutationResult> resp = milvusClient.insert(insertParam);

            if (resp.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Failed to insert vectors: " + resp.getMessage());
            }

            log.debug("Inserted {} vectors into collection [{}]", size, collectionName);

        } catch (Exception e) {
            log.error("Failed to insert vectors: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to insert vectors into Milvus", e);
        }
    }

    /**
     * 向量相似性检索。
     *
     * @param queryVector 查询向量
     * @param topK        返回最相似的 K 个结果
     * @return 检索结果列表
     */
    public List<SearchResult> searchVectors(List<Float> queryVector, int topK) {
        return searchVectors(queryVector, topK, null);
    }

    /**
     * 向量相似性检索（带过滤条件）。
     *
     * @param queryVector  查询向量
     * @param topK         返回最相似的 K 个结果
     * @param filterExpr   过滤表达式，如 document_id == "xxx"，为 null 则不过滤
     * @return 检索结果列表
     */
    public List<SearchResult> searchVectors(List<Float> queryVector, int topK, String filterExpr) {
        if (milvusClient == null) {
            log.warn("Milvus client not available, returning empty results");
            return Collections.emptyList();
        }
        if (queryVector == null || queryVector.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            SearchParam.Builder searchParamBuilder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectors(List.of(queryVector))
                    .withVectorFieldName(EMBEDDING_FIELD)
                    .withTopK(topK)
                    .withMetricType(METRIC_TYPE)
                    .withOutFields(List.of(ID_FIELD, DOCUMENT_ID_FIELD, CHUNK_INDEX_FIELD, CONTENT_FIELD))
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED);

            if (filterExpr != null && !filterExpr.isBlank()) {
                searchParamBuilder.withExpr(filterExpr);
            }

            R<SearchResults> resp = milvusClient.search(searchParamBuilder.build());

            if (resp.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Search failed: " + resp.getMessage());
            }

            return parseSearchResults(resp.getData());

        } catch (Exception e) {
            log.error("Vector search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Milvus vector search failed", e);
        }
    }

    /**
     * 根据文档 ID 删除该文档的所有向量。
     *
     * @param documentId 文档 ID
     */
    public void deleteVectors(String documentId) {
        if (milvusClient == null) {
            log.warn("Milvus client not available, skipping vector delete");
            return;
        }
        if (documentId == null || documentId.isBlank()) {
            return;
        }

        try {
            String expr = DOCUMENT_ID_FIELD + " == \"" + documentId + "\"";
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();

            R<io.milvus.grpc.MutationResult> resp = milvusClient.delete(deleteParam);

            if (resp.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Delete failed: " + resp.getMessage());
            }

            log.debug("Deleted vectors for document [{}] from collection [{}]",
                    documentId, collectionName);

        } catch (Exception e) {
            log.error("Failed to delete vectors for document [{}]: {}", documentId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete vectors from Milvus", e);
        }
    }

    /**
     * 根据多个块 ID 删除向量。
     *
     * @param ids 块 ID 列表
     */
    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        try {
            String inClause = ids.stream()
                    .map(id -> "\"" + id + "\"")
                    .collect(Collectors.joining(", "));
            String expr = ID_FIELD + " in [" + inClause + "]";

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build();

            R<io.milvus.grpc.MutationResult> resp = milvusClient.delete(deleteParam);

            if (resp.getStatus() != R.Status.Success.getCode()) {
                throw new RuntimeException("Delete by IDs failed: " + resp.getMessage());
            }

            log.debug("Deleted {} vectors by ID from collection [{}]", ids.size(), collectionName);

        } catch (Exception e) {
            log.error("Failed to delete vectors by IDs: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to delete vectors by IDs from Milvus", e);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 确保向量索引存在。
     */
    private void ensureIndex() {
        try {
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName(EMBEDDING_FIELD)
                    .withIndexType(INDEX_TYPE)
                    .withMetricType(METRIC_TYPE)
                    .withExtraParam(String.format("{\"nlist\":%d}", NLIST))
                    .withSyncMode(true)
                    .build();

            R<RpcStatus> resp = milvusClient.createIndex(indexParam);

            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("Create index response: {}", resp.getMessage());
            }

            log.info("Index created/verified for collection [{}]: type={}, metric={}",
                    collectionName, INDEX_TYPE, METRIC_TYPE);

        } catch (Exception e) {
            log.warn("Index may already exist for collection [{}]: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 将集合加载到内存。
     */
    private void loadCollection() {
        try {
            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();

            R<RpcStatus> resp = milvusClient.loadCollection(loadParam);

            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("Load collection response: {}", resp.getMessage());
            }

            log.info("Collection [{}] loaded into memory", collectionName);

        } catch (Exception e) {
            log.warn("Failed to load collection [{}]: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 解析 Milvus 检索结果为 SearchResult 列表。
     */
    private List<SearchResult> parseSearchResults(SearchResults searchResults) {
        List<SearchResult> results = new ArrayList<>();

        try {
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());

            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            for (SearchResultsWrapper.IDScore score : scores) {
                Map<String, Object> fieldValues = score.getFieldValues();

                String docId = String.valueOf(fieldValues.getOrDefault(DOCUMENT_ID_FIELD, ""));
                long chunkIdx = fieldValues.containsKey(CHUNK_INDEX_FIELD)
                        ? ((Number) fieldValues.get(CHUNK_INDEX_FIELD)).longValue() : 0L;
                String content = String.valueOf(fieldValues.getOrDefault(CONTENT_FIELD, ""));

                SearchResult result = SearchResult.builder()
                        .documentId(docId)
                        .chunkIndex((int) chunkIdx)
                        .content(content)
                        .score(score.getScore())
                        .source("dense")
                        .build();

                results.add(result);
            }

        } catch (Exception e) {
            log.error("Failed to parse search results: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * 校验列表大小一致性。
     */
    private void validateListSize(String name, List<?> list, int expected) {
        if (list == null || list.size() != expected) {
            throw new IllegalArgumentException(
                    String.format("List [%s] size mismatch: expected %d, got %s",
                            name, expected, list == null ? "null" : list.size()));
        }
    }
}
