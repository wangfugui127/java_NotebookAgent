package com.wangfugui.spike.milvus;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.FunctionScore;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase 0 probe: reproduce the Python `document_chunks_v3` schema with the Java SDK:
 * multi-analyzer content field, BM25 Function(content->sparse), dense+sparse indexes,
 * then a dense+sparse hybrid search through an RRFRanker(60).
 */
public final class MilvusProbe {

    private static final String COLLECTION = "spike_document_chunks_v3";
    private static final int DIM = 1024;

    public static void main(String[] args) {
        String uri = System.getProperty("milvus.uri", "http://localhost:19531");
        System.out.println("[milvus] connecting to " + uri);
        MilvusClientV2 client = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
        try {
            if (client.hasCollection(HasCollectionReq.builder().collectionName(COLLECTION).build())) {
                client.dropCollection(DropCollectionReq.builder().collectionName(COLLECTION).build());
            }
            createCollection(client);
            insertRows(client);
            long count = rowCount(client);
            System.out.println("[milvus] PASS create+insert, row count=" + count);

            hybridSearch(client);
            textOnlySearch(client);
            System.out.println("[milvus] ALL CAPABILITIES OK (multi-analyzer + BM25 function + hybrid RRF)");
        } finally {
            client.close();
        }
    }

    private static void createCollection(MilvusClientV2 client) {
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

        // content field: mirrors the Python multi_analyzer_params exactly.
        Map<String, Object> analyzers = new LinkedHashMap<>();
        analyzers.put("english", Map.of("type", "english"));
        analyzers.put("chinese", Map.of("type", "chinese"));
        analyzers.put("default", Map.of("tokenizer", "icu", "filter", List.of("removepunct")));
        Map<String, Object> multiAnalyzer = new LinkedHashMap<>();
        multiAnalyzer.put("analyzers", analyzers);
        multiAnalyzer.put("by_field", "language");
        multiAnalyzer.put("alias", Map.of("en", "english", "zh", "chinese"));

        schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true).multiAnalyzerParams(multiAnalyzer).build());
        schema.addField(AddFieldReq.builder().fieldName("dense").dataType(DataType.FloatVector).dimension(DIM).build());
        schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
        schema.addField(AddFieldReq.builder().fieldName("is_active").dataType(DataType.Bool).build());

        schema.addFunction(CreateCollectionReq.Function.builder()
                .functionType(FunctionType.BM25)
                .name("content_bm25")
                .inputFieldNames(Collections.singletonList("content"))
                .outputFieldNames(Collections.singletonList("sparse"))
                .build());

        List<IndexParam> indexes = new ArrayList<>();
        indexes.add(IndexParam.builder().fieldName("dense")
                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build());
        indexes.add(IndexParam.builder().fieldName("sparse")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25).build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION)
                .collectionSchema(schema)
                .indexParams(indexes)
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build());
        System.out.println("[milvus] collection created with multi-analyzer + BM25 function");
    }

    private static void insertRows(MilvusClientV2 client) {
        Gson gson = new Gson();
        String[][] texts = {
                {"nb1", "zh", "Presto 使用掩码自编码器在 21.5M 像素时间序列上预训练。"},
                {"nb1", "zh", "AlphaEarth Foundations 生成 10m 分辨率的嵌入场。"},
                {"nb1", "en", "Milvus is an open-source vector database supporting hybrid search."},
                {"nb1", "en", "BM25 full-text retrieval fuses with dense vectors through RRF."},
        };
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("chunk_id", "chunk-" + i);
            row.addProperty("notebook_id", texts[i][0]);
            row.addProperty("document_id", "doc-" + i);
            row.addProperty("document_version_id", "ver-" + i);
            row.addProperty("section_id", "sec-" + i);
            row.addProperty("ordinal", i);
            row.addProperty("title", "doc " + i);
            row.addProperty("language", texts[i][1]);
            row.addProperty("page_start", i + 1);
            row.addProperty("page_end", i + 1);
            row.addProperty("chunk_type", "paragraph");
            row.add("block_ids", gson.toJsonTree(List.of("block-" + i)));
            row.addProperty("content", texts[i][2]);
            row.add("dense", gson.toJsonTree(randomVector(i + 1)));
            row.addProperty("is_active", true);
            rows.add(row);
        }
        client.insert(InsertReq.builder().collectionName(COLLECTION).data(rows).build());
    }

    private static long rowCount(MilvusClientV2 client) {
        QueryResp r = client.query(QueryReq.builder().collectionName(COLLECTION)
                .outputFields(Collections.singletonList("count(*)")).consistencyLevel(ConsistencyLevel.STRONG).build());
        return (long) r.getQueryResults().get(0).getEntity().get("count(*)");
    }

    private static void hybridSearch(MilvusClientV2 client) {
        String query = "掩码自编码器像素时间序列";
        List<BaseVector> denseQ = List.of(new FloatVec(randomVector(7)));
        List<BaseVector> sparseQ = List.of(new EmbeddedText(query));
        List<AnnSearchReq> reqs = new ArrayList<>();
        reqs.add(AnnSearchReq.builder().vectorFieldName("dense").vectors(denseQ)
                .params("{\"metric_type\":\"COSINE\"}").limit(30)
                .filter("notebook_id == \"nb1\" and is_active == true").build());
        reqs.add(AnnSearchReq.builder().vectorFieldName("sparse").vectors(sparseQ)
                .params("{\"metric_type\":\"BM25\"}").limit(30)
                .filter("notebook_id == \"nb1\" and is_active == true").build());
        SearchResp resp = client.hybridSearch(HybridSearchReq.builder()
                .collectionName(COLLECTION)
                .searchRequests(reqs)
                .functionScore(FunctionScore.builder()
                        .addFunction(RRFRanker.builder().k(60).build())
                        .build())
                .limit(10)
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .build());
        List<SearchResp.SearchResult> hits = resp.getSearchResults().get(0);
        System.out.println("[milvus] hybrid RRF hits=" + hits.size());
        for (SearchResp.SearchResult hit : hits) {
            System.out.println("        " + hit.getEntity().get("chunk_id") + " score=" + hit.getScore()
                    + " -> " + hit.getEntity().get("content"));
        }
        if (hits.isEmpty()) {
            throw new IllegalStateException("hybrid search returned no hits");
        }
    }

    private static void textOnlySearch(MilvusClientV2 client) {
        Map<String, Object> params = new HashMap<>();
        params.put("analyzer_name", "chinese");
        params.put("anns_field", "sparse");
        SearchResp resp = client.search(SearchReq.builder()
                .collectionName(COLLECTION)
                .annsField("sparse")
                .data(Collections.singletonList(new EmbeddedText("嵌入场")))
                .limit(3)
                .searchParams(params)
                .outputFields(List.of("chunk_id", "content", "language"))
                .build());
        int n = resp.getSearchResults().get(0).size();
        System.out.println("[milvus] chinese-analyzer full-text search hits=" + n);
        if (n == 0) {
            throw new IllegalStateException("full-text search returned no hits");
        }
    }

    private static List<Float> randomVector(long seed) {
        Random r = new Random(seed);
        List<Float> v = new ArrayList<>(DIM);
        for (int i = 0; i < DIM; i++) {
            v.add(r.nextFloat());
        }
        return v;
    }

    private MilvusProbe() {
    }
}
