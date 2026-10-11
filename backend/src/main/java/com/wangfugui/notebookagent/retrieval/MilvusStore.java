package com.wangfugui.notebookagent.retrieval;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wangfugui.notebookagent.config.AppProperties;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.FunctionScore;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Milvus chunk/document collections boundary (dense + BM25 sparse hybrid search). */
public class MilvusStore {

    public record MilvusHit(String id, double score) {
    }

    private static final Gson GSON = new Gson();
    private final AppProperties.Milvus config;
    private final int dim;
    private final String chunkCollection;
    private final String documentCollection;
    private MilvusClientV2 client;

    public MilvusStore(AppProperties props) {
        this.config = props.getMilvus();
        this.dim = props.getSiliconflow().getEmbeddingDim();
        this.chunkCollection = config.getCollection();
        this.documentCollection = config.getDocumentCollection();
        this.client = null;
    }

    private synchronized MilvusClientV2 client() {
        if (client == null) {
            client = new MilvusClientV2(ConnectConfig.builder().uri(config.getUri()).build());
        }
        return client;
    }

    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    // ------------------------------------------------------------------ schema

    private static Map<String, Object> multiAnalyzerParams() {
        Map<String, Object> analyzers = new LinkedHashMap<>();
        analyzers.put("english", Map.of("type", "english"));
        analyzers.put("chinese", Map.of("type", "chinese"));
        analyzers.put("default", Map.of("tokenizer", "icu", "filter", List.of("removepunct")));
        Map<String, Object> multi = new LinkedHashMap<>();
        multi.put("analyzers", analyzers);
        multi.put("by_field", "language");
        multi.put("alias", Map.of("en", "english", "zh", "chinese"));
        return multi;
    }

    public synchronized void ensureChunkCollection() {
        MilvusClientV2 c = client();
        if (c.hasCollection(HasCollectionReq.builder().collectionName(chunkCollection).build())) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("chunk_id").dataType(DataType.VarChar)
                .maxLength(64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("notebook_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("document_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("document_version_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("section_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("ordinal").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("title").dataType(DataType.VarChar).maxLength(1024).build());
        schema.addField(AddFieldReq.builder().fieldName("language").dataType(DataType.VarChar).maxLength(24).build());
        schema.addField(AddFieldReq.builder().fieldName("page_start").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("page_end").dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName("chunk_type").dataType(DataType.VarChar).maxLength(24).build());
        schema.addField(AddFieldReq.builder().fieldName("block_ids").dataType(DataType.JSON).build());
        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).multiAnalyzerParams(multiAnalyzerParams()).build());
        schema.addField(AddFieldReq.builder().fieldName("dense").dataType(DataType.FloatVector).dimension(dim).build());
        schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
        schema.addField(AddFieldReq.builder().fieldName("is_active").dataType(DataType.Bool).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25).name("content_bm25")
                .inputFieldNames(List.of("content")).outputFieldNames(List.of("sparse")).build());
        c.createCollection(CreateCollectionReq.builder()
                .collectionName(chunkCollection).collectionSchema(schema)
                .indexParams(List.of(
                        IndexParam.builder().fieldName("dense")
                                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build(),
                        IndexParam.builder().fieldName("sparse")
                                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25).build()))
                .consistencyLevel(ConsistencyLevel.STRONG).build());
    }

    public synchronized void ensureDocumentCollection() {
        MilvusClientV2 c = client();
        if (c.hasCollection(HasCollectionReq.builder().collectionName(documentCollection).build())) {
            return;
        }
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder().fieldName("profile_id").dataType(DataType.VarChar)
                .maxLength(80).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder().fieldName("notebook_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("document_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("document_version_id").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder().fieldName("title").dataType(DataType.VarChar).maxLength(1024).build());
        schema.addField(AddFieldReq.builder().fieldName("language").dataType(DataType.VarChar).maxLength(24).build());
        schema.addField(AddFieldReq.builder().fieldName("is_active").dataType(DataType.Bool).build());
        schema.addField(AddFieldReq.builder().fieldName("text").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).multiAnalyzerParams(multiAnalyzerParams()).build());
        schema.addField(AddFieldReq.builder().fieldName("dense").dataType(DataType.FloatVector).dimension(dim).build());
        schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25).name("text_bm25")
                .inputFieldNames(List.of("text")).outputFieldNames(List.of("sparse")).build());
        c.createCollection(CreateCollectionReq.builder()
                .collectionName(documentCollection).collectionSchema(schema)
                .indexParams(List.of(
                        IndexParam.builder().fieldName("dense")
                                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build(),
                        IndexParam.builder().fieldName("sparse")
                                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25).build()))
                .consistencyLevel(ConsistencyLevel.STRONG).build());
    }

    // ------------------------------------------------------------------ writes

    public void upsertChunks(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        ensureChunkCollection();
        MilvusClientV2 c = client();
        List<JsonObject> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            data.add(GSON.toJsonTree(row).getAsJsonObject());
        }
        c.upsert(UpsertReq.builder().collectionName(chunkCollection).data(data).build());
    }

    public void upsertDocuments(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        ensureDocumentCollection();
        MilvusClientV2 c = client();
        List<JsonObject> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            data.add(GSON.toJsonTree(row).getAsJsonObject());
        }
        c.upsert(UpsertReq.builder().collectionName(documentCollection).data(data).build());
    }

    public void deleteByDocument(String documentId) {
        MilvusClientV2 c = client();
        String expr = "document_id == \"" + documentId + "\"";
        for (String collection : List.of(chunkCollection, documentCollection)) {
            try {
                if (c.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
                    c.delete(DeleteReq.builder().collectionName(collection).filter(expr).build());
                }
            } catch (Exception ignored) {
                // external store failure is non-fatal
            }
        }
    }

    // ------------------------------------------------------------------ search

    public List<MilvusHit> hybridSearch(String query, float[] vector, String notebookId,
                                        List<String> documentIds, List<String> sectionIds, int limit) {
        if (documentIds != null && documentIds.isEmpty()) return List.of();
        ensureChunkCollection();
        MilvusClientV2 c = client();
        String expr = filter(notebookId, documentIds, sectionIds);
        List<BaseVector> dense = List.of(new FloatVec(toList(vector)));
        List<BaseVector> sparse = List.of(new EmbeddedText(query));
        List<AnnSearchReq> reqs = new ArrayList<>();
        reqs.add(AnnSearchReq.builder().vectorFieldName("dense").vectors(dense)
                .params("{\"metric_type\":\"COSINE\"}").limit(limit).filter(expr).build());
        reqs.add(AnnSearchReq.builder().vectorFieldName("sparse").vectors(sparse)
                .params("{\"metric_type\":\"BM25\"}").limit(limit).filter(expr).build());
        SearchResp resp = c.hybridSearch(HybridSearchReq.builder()
                .collectionName(chunkCollection).searchRequests(reqs)
                .functionScore(FunctionScore.builder().addFunction(RRFRanker.builder().k(60).build()).build())
                .limit(limit).consistencyLevel(ConsistencyLevel.STRONG).build());
        return toHits(resp);
    }

    public List<MilvusHit> searchDocuments(String query, float[] vector, String notebookId,
                                           List<String> documentIds, int limit) {
        if (documentIds != null && documentIds.isEmpty()) return List.of();
        ensureDocumentCollection();
        MilvusClientV2 c = client();
        StringBuilder expr = new StringBuilder("notebook_id == \"").append(notebookId).append("\" and is_active == true");
        if (documentIds != null && !documentIds.isEmpty()) {
            expr.append(" and document_id in [").append(quoteJoin(documentIds)).append("]");
        }
        List<BaseVector> dense = List.of(new FloatVec(toList(vector)));
        List<BaseVector> sparse = List.of(new EmbeddedText(query));
        List<AnnSearchReq> reqs = new ArrayList<>();
        reqs.add(AnnSearchReq.builder().vectorFieldName("dense").vectors(dense)
                .params("{\"metric_type\":\"COSINE\"}").limit(limit).filter(expr.toString()).build());
        reqs.add(AnnSearchReq.builder().vectorFieldName("sparse").vectors(sparse)
                .params("{\"metric_type\":\"BM25\"}").limit(limit).filter(expr.toString()).build());
        SearchResp resp = c.hybridSearch(HybridSearchReq.builder()
                .collectionName(documentCollection).searchRequests(reqs)
                .functionScore(FunctionScore.builder().addFunction(RRFRanker.builder().k(60).build()).build())
                .limit(limit).consistencyLevel(ConsistencyLevel.STRONG).build());
        return toHits(resp);
    }

    private List<MilvusHit> toHits(SearchResp resp) {
        List<MilvusHit> hits = new ArrayList<>();
        if (resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return hits;
        }
        for (SearchResp.SearchResult result : resp.getSearchResults().get(0)) {
            Object id = result.getId();
            hits.add(new MilvusHit(id == null ? null : String.valueOf(id), result.getScore()));
        }
        return hits;
    }

    private String filter(String notebookId, List<String> documentIds, List<String> sectionIds) {
        StringBuilder expr = new StringBuilder("notebook_id == \"").append(notebookId)
                .append("\" and is_active == true");
        if (documentIds != null && !documentIds.isEmpty()) {
            expr.append(" and document_id in [").append(quoteJoin(documentIds)).append("]");
        }
        if (sectionIds != null && !sectionIds.isEmpty()) {
            expr.append(" and section_id in [").append(quoteJoin(sectionIds)).append("]");
        }
        return expr.toString();
    }

    private static String quoteJoin(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ids.get(i)).append('"');
        }
        return sb.toString();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    public void dropAll() {
        MilvusClientV2 c = client();
        for (String collection : List.of(chunkCollection, documentCollection)) {
            if (c.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
                c.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            }
        }
    }

    public void loadChunkCollection() {
        client().loadCollection(LoadCollectionReq.builder().collectionName(chunkCollection).build());
    }
}
