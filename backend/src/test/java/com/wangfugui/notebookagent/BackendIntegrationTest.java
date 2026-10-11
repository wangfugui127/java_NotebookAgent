package com.wangfugui.notebookagent;

import com.wangfugui.notebookagent.agent.*;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.*;
import com.wangfugui.notebookagent.domain.*;
import com.wangfugui.notebookagent.ingestion.*;
import com.wangfugui.notebookagent.providers.*;
import com.wangfugui.notebookagent.queue.*;
import com.wangfugui.notebookagent.repository.*;
import com.wangfugui.notebookagent.retrieval.*;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.MockedConstruction;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;

/** Real MySQL + Redis; provider/derived-store faults are injected explicitly. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class BackendIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("regression").withUsername("tester").withPassword("tester");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final Path UPLOADS;
    static { try { UPLOADS = Files.createTempDirectory("java-notebook-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
        p.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        p.add("app.upload-dir", () -> UPLOADS.toString());
        p.add("app.mcp-servers-config", () -> UPLOADS.resolve("missing.yaml").toString());
        p.add("app.skills-config", () -> UPLOADS.resolve("missing-skills.yaml").toString());
        p.add("app.queue.key-prefix", () -> "regression");
        p.add("app.deepseek.api-key", () -> "test-only");
        p.add("app.siliconflow.embedding-dim", () -> 8);
    }
    @MockitoBean TaskDispatcher scheduledDispatcher;
    @Autowired MockMvc mvc;
    @Autowired AppProperties props;
    @Autowired UserRepository users;
    @Autowired NotebookRepository notebooks;
    @Autowired ConversationRepository conversations;
    @Autowired AgentRunRepository runs;
    @Autowired MessageRepository messages;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired ChunkRepository chunks;
    @Autowired EvidenceRepository evidence;
    @Autowired CitationRepository citations;
    @Autowired ApprovalRequestRepository approvals;
    @Autowired ToolCallRepository toolCalls;
    @Autowired TaskExecutionRepository tasks;
    @Autowired RunEventRepository runEvents;
    @Autowired TaskQueue queue;
    @Autowired RunService runService;
    @Autowired SourceService sources;
    @Autowired DeleteService deletes;
    @Autowired IngestionService ingestion;
    @Autowired AgentRuntime runtime;
    @Autowired EventStore events;
    @Autowired com.wangfugui.notebookagent.evaluation.EvalService evaluations;
    @Autowired EvalDatasetRepository datasets;
    @Autowired EvalCaseRepository evalCases;
    @Autowired EvalRunRepository evalRuns;
    @Autowired EvalCaseResultRepository evalResults;
    @Autowired TokenService tokens;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager tm;
    @LocalServerPort int port;
    record Fixture(User user, Notebook notebook, Conversation conversation) {}
    Fixture fixture() {
        User u = new User(); u.setEmail(UUID.randomUUID()+"@example.test"); u.setPasswordHash(PasswordHasher.hash("password123")); users.save(u);
        Notebook n = new Notebook(); n.setOwnerId(u.getId()); n.setTitle("测试资料"); notebooks.save(n);
        Conversation c = new Conversation(); c.setUserId(u.getId()); c.setNotebookId(n.getId()); conversations.save(c);
        return new Fixture(u,n,c);
    }
    AgentRun run(Fixture f, String mode) { return runService.createRun(f.user(), f.conversation(), "测试执行", List.of(), Map.of(), mode); }
    String auth(User u) { return "Bearer " + tokens.accessToken(u.getId()); }
    ProviderResponse response(String content, ProviderToolCall... calls) { return new ProviderResponse(content,List.of(calls),Map.of(),Map.of()); }
    SourceService.Created source(Fixture f) { return sources.createSource(f.notebook().getId(),"sample.md","text/markdown", "# 测试\n\n准确结论：样本库存为 10。".getBytes(java.nio.charset.StandardCharsets.UTF_8),".md"); }
    MockedConstruction<SiliconFlowProvider> embeddings() { return mockConstruction(SiliconFlowProvider.class, (m,c) -> when(m.embed(anyList())).thenAnswer(a -> ((List<?>)a.getArgument(0)).stream().map(x -> new float[8]).toList())); }

    @Test void validationAndOwnershipAreEnforced() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{\"email\":\"bad\",\"password\":\"x\"}")).andExpect(status().is4xxClientError());
        Fixture f = fixture(), other = fixture();
        mvc.perform(get("/api/v1/notebooks/"+f.notebook().getId()+"/documents").header("Authorization",auth(other.user()))).andExpect(status().isNotFound());
        var foreign = source(other);
        long before = messages.countByConversationId(f.conversation().getId());
        assertThatThrownBy(() -> runService.createRun(f.user(), f.conversation(), "bad",List.of(foreign.documentId()),Map.of(),"confirm")).isInstanceOf(ApiException.class);
        assertThat(messages.countByConversationId(f.conversation().getId())).isEqualTo(before);
    }
    @Test void refreshTokensRotateAndCannotBeReused() throws Exception {
        String registration = mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(Json.write(Map.of("email",UUID.randomUUID()+"@example.test","password","password123"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String refresh = String.valueOf(Json.readMap(registration).get("refresh_token"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> rotate = () -> mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(Json.write(Map.of("refresh_token",refresh)))) .andReturn().getResponse().getStatus();
            var results = pool.invokeAll(List.of(rotate,rotate));
            assertThat(results.stream().map(r -> { try{return r.get();}catch(Exception e){throw new RuntimeException(e);} }).toList()).containsExactlyInAnyOrder(200,401);
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentIntakeDeduplicatesAtomically() throws Exception {
        String key = "parallel:"+UUID.randomUUID(); ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = new ArrayList<>(); for(int i=0;i<16;i++) calls.add(() -> queue.enqueue("probe",Map.of(),key,3).getId());
            Set<String> ids = new HashSet<>(); for(var f:pool.invokeAll(calls)) ids.add(f.get());
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void transactionRollbackRemovesSourceFileAndOutbox() throws Exception {
        Fixture f=fixture(); String[] ids = new String[3];
        new TransactionTemplate(tm).executeWithoutResult(tx -> {
            var s=source(f); ids[0]=s.documentId(); ids[1]=s.versionId(); ids[2]=tasks.findByDedupKey("ingest:"+s.versionId()+":"+versions.findById(s.versionId()).orElseThrow().getContentHash()).orElseThrow().getId(); tx.setRollbackOnly();
        });
        assertThat(documents.findById(ids[0])).isEmpty(); assertThat(versions.findById(ids[1])).isEmpty(); assertThat(tasks.findById(ids[2])).isEmpty();
        assertThat(UPLOADS.resolve(f.notebook().getId()).resolve(ids[0])).doesNotExist();
    }
    @Test void leasesFenceStaleOwnersAndRecoverExpiredAttempts() {
        TaskExecution task = queue.enqueue("probe",Map.of(),"lease:"+UUID.randomUUID(),3); Instant now=Instant.now();
        assertThat(tasks.claim(task.getId(),"first",now.plusSeconds(20),now)).isEqualTo(1);
        assertThat(tasks.claim(task.getId(),"second",now.plusSeconds(20),now)).isZero();
        assertThat(tasks.markSucceeded(task.getId(),"wrong",now)).isZero();
        tasks.claim(task.getId(),"second",now.plusSeconds(60),now.plusSeconds(21));
        assertThat(tasks.renewLease(task.getId(),"first",now.plusSeconds(100),now.plusSeconds(22))).isZero();
        assertThat(tasks.markSucceeded(task.getId(),"first",now.plusSeconds(22))).isZero();
        assertThat(tasks.markSucceeded(task.getId(),"second",now.plusSeconds(22))).isEqualTo(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getAttempt()).isEqualTo(2);
    }
    @Test void redisPendingMessagesAreReclaimedAfterConsumerCrash() throws Exception {
        AppProperties q = new AppProperties(); q.getQueue().setKeyPrefix("reclaim:"+UUID.randomUUID()); q.getQueue().setWorkerEnabled(true);
        q.getQueue().setVisibilityTimeoutSeconds(1); q.getQueue().setLeaseSeconds(3); q.getQueue().setPollBlockMs(100);
        String stream=q.getQueue().getKeyPrefix()+":tasks";
        redis.opsForStream().add(MapRecord.create(stream,Map.of("init","1"))); redis.opsForStream().createGroup(stream,ReadOffset.from("0"),"workers");
        TaskExecution task=queue.enqueue("probe",Map.of(),"crash:"+UUID.randomUUID(),3);
        redis.opsForStream().add(MapRecord.create(stream,Map.of("task_id",task.getId())));
        redis.opsForStream().read(Consumer.from("workers","dead-consumer"),StreamReadOptions.empty().count(10),StreamOffset.create(stream,ReadOffset.lastConsumed()));
        Instant past=Instant.now().minusSeconds(5); tasks.claim(task.getId(),"dead-owner",past.plusSeconds(1),past);
        AtomicInteger executions=new AtomicInteger();
        TaskHandler h=new TaskHandler(){public String type(){return "probe";}public void handle(String id,Map<String,Object> p){executions.incrementAndGet();}};
        TaskWorker worker=new TaskWorker(q,redis,tasks,List.of(h)); worker.start();
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskExecution.SUCCEEDED));
            assertThat(executions.get()).isEqualTo(1); assertThat(tasks.findById(task.getId()).orElseThrow().getAttempt()).isEqualTo(2);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(redis.opsForStream().pending(stream,"workers").getTotalPendingMessages()).isZero());
        } finally { worker.stop(); redis.delete(stream); }
    }
    @Test void milvusFailureCannotPublishReadyDocument() {
        Fixture f=fixture(); var s=source(f);
        try(var embed=embeddings();var mv=mockConstruction(MilvusStore.class,(m,c)->doThrow(new IllegalStateException("injected Milvus failure")).when(m).upsertChunks(anyList()));
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenReturn(response("{}")))) {
            assertThatThrownBy(()->ingestion.ingestDocument(s.versionId())).hasMessageContaining("injected Milvus failure");
            assertThat(versions.findById(s.versionId()).orElseThrow().getStatus()).isEqualTo("failed");
            assertThat(documents.findById(s.documentId()).orElseThrow().getActiveVersionId()).isNull();
        }
    }
    @Test void graphFailureDoesNotInvalidateReadyDocument() {
        Fixture f=fixture();var s=source(f);
        try(var embed=embeddings();var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenReturn(response("{}")))) {
            ingestion.ingestDocument(s.versionId()); ingestion.buildGraphIndex(s.versionId());
            DocumentVersion v=versions.findById(s.versionId()).orElseThrow(); assertThat(v.getStatus()).isEqualTo("ready"); assertThat(v.getGraphStatus()).isEqualTo("failed");
            assertThat(tasks.findByDedupKey("graph:"+s.versionId())).isPresent();
        }
    }
    @Test void approvalResumesWholeBatchWithoutRepeatingCompletedTools() throws Exception {
        Fixture f=fixture();var s=source(f);AgentRun run=run(f,"confirm");AtomicInteger turns=new AtomicInteger();
        try(var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenAnswer(a->{
                List<?> schemas=a.getArgument(1); if(schemas.isEmpty())return response("{\"route\":\"no_rag\"}");
                if(turns.getAndIncrement()==0)return response("",new ProviderToolCall("read-before","list_notebook_sources",Map.of()),new ProviderToolCall("write-middle","remove_notebook_source",Map.of("document_ids",List.of(s.documentId()))),new ProviderToolCall("read-after","list_notebook_sources",Map.of()));
                List<Map<String,Object>> working=a.getArgument(0);
                assertThat(working.stream().filter(x->"tool".equals(x.get("role"))).count()).isEqualTo(3);return response("已完成");
            }))) {
            runtime.execute(run.getId()); assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("waiting_approval");
            ApprovalRequest approval=approvals.findByRunId(run.getId()).getFirst();
            assertThat(approval.getToolCallId()).isEqualTo(toolCalls.findByRunIdAndProviderCallId(run.getId(),"write-middle").orElseThrow().getId());
            runtime.execute(run.getId()); assertThat(turns.get()).isEqualTo(1);
            mvc.perform(post("/api/v1/agent/approvals/"+approval.getId()+"/resolve").header("Authorization",auth(f.user())).contentType("application/json").content("{\"decision\":\"approved\"}")).andExpect(status().isAccepted());
            mvc.perform(post("/api/v1/agent/approvals/"+approval.getId()+"/resolve").header("Authorization",auth(f.user())).contentType("application/json").content("{\"decision\":\"approved\"}")).andExpect(status().isConflict());
            runtime.execute(run.getId()); assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("completed");
            assertThat(documents.findById(s.documentId())).isEmpty();
            assertThat(toolCalls.findByRunId(run.getId())).hasSize(3);
            runtime.execute(run.getId()); assertThat(messages.findByConversationIdAndRoleInOrderByCreatedAtAscIdAsc(f.conversation().getId(),List.of("assistant"))).hasSize(1);
        }
    }
    @Test void rejectedApprovalNeverRunsDestructiveTool() throws Exception {
        Fixture f=fixture();var s=source(f);AgentRun run=run(f,"confirm");AtomicInteger turns=new AtomicInteger();
        try(var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenAnswer(a->{
                if(((List<?>)a.getArgument(1)).isEmpty())return response("{\"route\":\"no_rag\"}");
                return turns.getAndIncrement()==0?response("",new ProviderToolCall("reject","remove_notebook_source",Map.of("document_ids",List.of(s.documentId())))):response("未执行删除");
            }))) {
            runtime.execute(run.getId()); var approval=approvals.findByRunId(run.getId()).getFirst();
            mvc.perform(post("/api/v1/agent/approvals/"+approval.getId()+"/resolve").header("Authorization",auth(f.user())).contentType("application/json").content("{\"decision\":\"rejected\"}")).andExpect(status().isAccepted());
            runtime.execute(run.getId()); assertThat(documents.findById(s.documentId())).isPresent();
            assertThat(toolCalls.findByRunId(run.getId()).getFirst().getStatus()).isEqualTo("rejected");
        }
    }
    @Test void toolEvidenceCreatesCitationAndBlocksForeignChunk() {
        Fixture f=fixture(), other=fixture();var s=source(f);var foreign=source(other);
        Chunk chunk=new Chunk();chunk.setDocumentVersionId(s.versionId());chunk.setContent("样本库存为10");chunk.setContentHash("hash");chunks.save(chunk);
        Chunk hidden=new Chunk();hidden.setDocumentVersionId(foreign.versionId());hidden.setContent("private");hidden.setContentHash("hidden");chunks.save(hidden);
        AgentRun run=run(f,"read_only");AtomicInteger turns=new AtomicInteger();
        try(var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenAnswer(a->{
                if(((List<?>)a.getArgument(1)).isEmpty())return response("{\"route\":\"no_rag\"}");
                if(turns.getAndIncrement()==0)return response("",new ProviderToolCall("get","get_notebook_items",Map.of("chunk_ids",List.of(chunk.getId(),hidden.getId()))));
                var rows=evidence.findByRunId(run.getId());assertThat(rows).hasSize(1);assertThat(rows.getFirst().getChunkId()).isEqualTo(chunk.getId());return response("库存为10 [evidence:"+rows.getFirst().getId()+"] [evidence:00000000-0000-0000-0000-000000000000]");
            }))) {
            runtime.execute(run.getId()); assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("completed");
            Message assistant=messages.findByConversationIdAndRoleInOrderByCreatedAtAscIdAsc(f.conversation().getId(),List.of("assistant")).getFirst();
            assertThat(assistant.getContent()).doesNotContain("00000000-0000"); assertThat(citations.findByMessageId(assistant.getId())).hasSize(1);
        }
    }
    @Test void runtimeFailureRemainsRetryableAndCanResume() {
        Fixture f=fixture();AgentRun run=run(f,"read_only");AtomicInteger turns=new AtomicInteger();
        try(var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenAnswer(a->{
                if(((List<?>)a.getArgument(1)).isEmpty())return response("{\"route\":\"no_rag\"}");
                if(turns.getAndIncrement()==0)throw new IllegalStateException("provider temporarily down");return response("恢复成功");
            }))) {
            assertThatThrownBy(()->runtime.execute(run.getId())).hasMessageContaining("provider temporarily down");
            runtime.recordFailure(run.getId(),"temporary",true); assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("pending");
            runtime.execute(run.getId());assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo("completed");
        }
    }
    @Test void evaluationSnapshotsScoresAndDoesNotRepeatCompletedRun() {
        Fixture f=fixture();EvalDataset dataset=new EvalDataset();dataset.setUserId(f.user().getId());dataset.setName("regression");datasets.save(dataset);
        EvalCase c=new EvalCase();c.setDatasetId(dataset.getId());c.setCaseKey("one");c.setQuestion("库存为多少");c.setRequiredKeywords("[\"库存\",\"10\"]");evalCases.save(c);
        var eval=evaluations.createRunSnapshot(f.user().getId(),f.notebook().getId(),dataset,List.of(c),Map.of(),com.wangfugui.notebookagent.evaluation.Scorers.DEFAULT_SCORE_CONFIG);
        try(var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,ctx)->when(m.invoke(anyList(),anyList(),any())).thenAnswer(a -> ((List<?>)a.getArgument(1)).isEmpty()?response("{\"route\":\"no_rag\"}"):response("库存为10")))) {
            evaluations.executeEvaluation(eval.getId());var finished=evalRuns.findById(eval.getId()).orElseThrow();
            assertThat(finished.getStatus()).isEqualTo("completed");assertThat(finished.getCompletedCases()).isEqualTo(1);assertThat(finished.getPassedCases()).isEqualTo(1);
            var result=evalResults.findByEvalRunIdOrderByOrdinalAsc(eval.getId()).getFirst();assertThat(result.getAnswer()).contains("10");assertThat(result.getAgentRunId()).isNotBlank();
            evaluations.executeEvaluation(eval.getId());assertThat(evalRuns.findById(eval.getId()).orElseThrow().getCompletedCases()).isEqualTo(1);
        }
    }
    @Test void deletingReadySourceCleansDatabaseEvenWhenDerivedStoresFail() {
        Fixture f=fixture();var s=source(f);
        try(var embed=embeddings();var mv=mockConstruction(MilvusStore.class);var neo=mockConstruction(Neo4jStore.class);
            var llm=mockConstruction(DeepSeekProvider.class,(m,c)->when(m.invoke(anyList(),anyList(),any())).thenReturn(response("{}")))) {
            ingestion.ingestDocument(s.versionId());
        }
        try(var mv=mockConstruction(MilvusStore.class,(m,c)->doThrow(new IllegalStateException("derived down")).when(m).deleteByDocument(anyString()));
            var neo=mockConstruction(Neo4jStore.class,(m,c)->doThrow(new IllegalStateException("graph down")).when(m).deleteDocumentVersion(anyString()))) {
            deletes.deleteSource(documents.findById(s.documentId()).orElseThrow());
            assertThat(documents.findById(s.documentId())).isEmpty();assertThat(versions.findById(s.versionId())).isEmpty();assertThat(chunks.findByDocumentVersionIdOrderByOrdinal(s.versionId())).isEmpty();
        }
    }
    @Test void concurrentEventsGetUniqueMonotonicSequences() throws Exception {
        Fixture f=fixture();AgentRun run=run(f,"read_only");ExecutorService pool=Executors.newFixedThreadPool(6);
        try { List<Callable<Void>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->{events.emit(run.getId(),"probe",Map.of());return null;});for(var r:pool.invokeAll(calls))r.get();
            assertThat(events.replay(run.getId(),0).stream().map(RunEvent::getSequence).toList()).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12);
        } finally{pool.shutdownNow();}
    }
    @Test void realMcpHttpTransportDiscoversToolsAndEnforcesNotebookOwnership() throws Exception {
        Fixture f=fixture(),other=fixture();
        var transport=HttpClientStreamableHttpTransport.builder("http://127.0.0.1:"+port).endpoint("/mcp").build();
        try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();assertThat(client.listTools().tools()).hasSize(5);
            var result=client.callTool(McpSchema.CallToolRequest.builder("list_notebook_sources").arguments(Map.of("notebook_id",f.notebook().getId(),"access_token",tokens.accessToken(f.user().getId()))).build());assertThat(result.isError()).isNotEqualTo(true);
            var denied=client.callTool(McpSchema.CallToolRequest.builder("list_notebook_sources").arguments(Map.of("notebook_id",f.notebook().getId(),"access_token",tokens.accessToken(other.user().getId()))).build());assertThat(denied.isError()).isTrue();
        }
        Path config=UPLOADS.resolve("http-mcp-"+UUID.randomUUID()+".yaml");Files.writeString(config,"servers:\n  - id: self\n    transport: streamable_http\n    url: http://127.0.0.1:"+port+"/mcp\n    default_risk: read\n");AppProperties p=new AppProperties();p.setMcpServersConfig(config.toString());var manager=new McpClientManager(p);assertThat(manager.discover()).hasSize(5);
    }

    @Test void sseReconnectUsesLastEventIdHeaderBeforeQueryCursor() throws Exception {
        Fixture f=fixture();AgentRun run=run(f,"confirm");
        events.emit(run.getId(),"run_started",Map.of());
        events.emit(run.getId(),"step",Map.of("index",1));
        events.emit(run.getId(),"run_completed",Map.of());
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/agent/runs/"+run.getId()+"/events?after=0"))
                .timeout(Duration.ofSeconds(10)).header("Authorization",auth(f.user())).header("Last-Event-ID","2").build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id:3","event:run_completed").doesNotContain("id:1", "id:2", "event:run_started");
    }
}
