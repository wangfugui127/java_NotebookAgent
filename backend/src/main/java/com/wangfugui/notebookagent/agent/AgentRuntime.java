package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.domain.ApprovalRequest;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.ConversationResourceSelection;
import com.wangfugui.notebookagent.domain.ConversationSummary;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.Evidence;
import com.wangfugui.notebookagent.domain.Message;
import com.wangfugui.notebookagent.domain.RunTranscript;
import com.wangfugui.notebookagent.domain.ToolCall;
import com.wangfugui.notebookagent.ingestion.DeleteService;
import com.wangfugui.notebookagent.ingestion.SourceService;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.OpenAlexProvider;
import com.wangfugui.notebookagent.providers.ProviderResponse;
import com.wangfugui.notebookagent.providers.ProviderToolCall;
import com.wangfugui.notebookagent.providers.SiliconFlowProvider;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.ApprovalRequestRepository;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.CitationRepository;
import com.wangfugui.notebookagent.repository.ConversationRepository;
import com.wangfugui.notebookagent.repository.ConversationResourceSelectionRepository;
import com.wangfugui.notebookagent.repository.ConversationSummaryRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.EvidenceRepository;
import com.wangfugui.notebookagent.repository.ExternalPaperResultRepository;
import com.wangfugui.notebookagent.repository.MessageRepository;
import com.wangfugui.notebookagent.repository.RunTranscriptRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;
import com.wangfugui.notebookagent.repository.TaskItemRepository;
import com.wangfugui.notebookagent.repository.ToolCallRepository;
import com.wangfugui.notebookagent.retrieval.MilvusStore;
import com.wangfugui.notebookagent.retrieval.Neo4jStore;
import com.wangfugui.notebookagent.retrieval.RetrievalService;
import com.wangfugui.notebookagent.retrieval.SearchHit;
import com.wangfugui.notebookagent.skills.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.wangfugui.notebookagent.queue.TaskLease;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes an agent run: context -> tool loop -> approvals -> citations -> completion. */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final Pattern EVIDENCE = Pattern.compile("\\[evidence:([0-9a-fA-F-]{36})\\]");
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_EXECUTIONS_PER_ROUND = 3;

    @Autowired private PlatformTransactionManager transactionManager;
    private EmbeddingToolRouter toolRouter;
    private final AppProperties props;
    private final AgentRunRepository runs;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationSummaryRepository summaries;
    private final ConversationResourceSelectionRepository selections;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final ChunkRepository chunks;
    private final SectionRepository sections;
    private final EvidenceRepository evidence;
    private final CitationRepository citations;
    private final ToolCallRepository toolCalls;
    private final ApprovalRequestRepository approvals;
    private final TaskItemRepository taskItems;
    private final RunTranscriptRepository transcripts;
    private final ExternalPaperResultRepository externalPapers;
    private final EventStore events;
    private final SkillCatalog skillCatalog;
    private final McpClientManager mcp;
    private final SourceService sourceService;
    private final DeleteService deleteService;

    public AgentRuntime(AppProperties props, AgentRunRepository runs, ConversationRepository conversations,
                        MessageRepository messages, ConversationSummaryRepository summaries,
                        ConversationResourceSelectionRepository selections, DocumentRepository documents,
                        DocumentVersionRepository versions, ChunkRepository chunks, SectionRepository sections,
                        EvidenceRepository evidence, CitationRepository citations, ToolCallRepository toolCalls,
                        ApprovalRequestRepository approvals, TaskItemRepository taskItems,
                        RunTranscriptRepository transcripts, ExternalPaperResultRepository externalPapers,
                        EventStore events, SkillCatalog skillCatalog, McpClientManager mcp,
                        SourceService sourceService, DeleteService deleteService) {
        this.props = props;
        this.runs = runs;
        this.conversations = conversations;
        this.messages = messages;
        this.summaries = summaries;
        this.selections = selections;
        this.documents = documents;
        this.versions = versions;
        this.chunks = chunks;
        this.sections = sections;
        this.evidence = evidence;
        this.citations = citations;
        this.toolCalls = toolCalls;
        this.approvals = approvals;
        this.taskItems = taskItems;
        this.transcripts = transcripts;
        this.externalPapers = externalPapers;
        this.events = events;
        this.skillCatalog = skillCatalog;
        this.mcp = mcp;
        this.sourceService = sourceService;
        this.deleteService = deleteService;
    }

    public void execute(String runId) {
        AgentRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            throw new IllegalStateException("run not found: " + runId);
        }
        if (Set.of("completed", "cancelled").contains(run.getStatus())) {
            return;
        }
        DeepSeekProvider provider = new DeepSeekProvider(props);
        Map<String, Object> stateMap = Json.readMap(run.getState());
        AgentState state = AgentState.fromMap(Json.readMap(Json.write(stateMap.getOrDefault("agent_state", Map.of()))));
        state.setRunId(run.getId());
        state.setNotebookId(run.getNotebookId());
        state.setUserId(run.getUserId());

        if (stateMap.get("pending_batch") instanceof Map<?, ?> batch) {
            String callId = String.valueOf(batch.get("call_id"));
            ToolCall call = toolCalls.findByRunIdAndProviderCallId(runId, callId).orElse(null);
            boolean unresolved = call != null && approvals.findByRunId(runId).stream()
                    .anyMatch(a -> call.getId().equals(a.getToolCallId()) && "pending".equals(a.getStatus()));
            if (unresolved) { run.setStatus("waiting_approval"); persistRun(run); return; }
        }
        run.setStatus("running");
        run.setErrorMessage(null);
        persistRun(run);
        events.emit(run.getId(), "run_started", Map.of());

        ToolContext context = null;
        try {
            ToolRegistry registry = new ToolRegistry();
            NativeTools.build(registry);
            registry.registerMcp(new ArrayList<>(mcp.tools().values()));

            context = buildContext(run, state, registry);
            String approvalMode = String.valueOf(stateMap.getOrDefault("approval_mode", "confirm"));
            String inputMessageId = str(stateMap.get("input_message_id"));

            List<Map<String, Object>> history = history(run, inputMessageId);
            String summaryText = summary(run);
            List<Map<String, Object>> attachments = stateMap.get("pending_batch") != null
                    ? List.of() : attachments(run);
            Map<String, String> instructions = Json.readStringMap(Json.write(stateMap.getOrDefault("attachment_instructions", Map.of())));

            List<Map<String, Object>> attachmentPayloads = new ArrayList<>();
            for (Map<String, Object> attachment : attachments) {
                attachmentPayloads.add(attachment);
            }

            for (Map<String, Object> attachment : attachmentPayloads) {
                attachment.put("instruction", instructions.getOrDefault(String.valueOf(attachment.get("document_id")), ""));
            }
            List<SearchHit> initial = initialRagEvidence(context, run, state);
            List<Map<String, Object>> evidenceRows = persistEvidence(run, initial, "notebook_chunk");
            for (Map<String, Object> attachment : attachmentPayloads) {
                List<SearchHit> hits = new ArrayList<>();
                for (Chunk chunk : chunks.findByDocumentVersionIdAndIsActiveTrueOrderByOrdinal(String.valueOf(attachment.get("document_version_id")))) {
                    SearchHit hit = new SearchHit();
                    hit.setChunkId(chunk.getId()); hit.setText(chunk.getContent()); hit.setDocumentVersionId(chunk.getDocumentVersionId());
                    hit.setPageStart(chunk.getPageStart()); hit.setPageEnd(chunk.getPageEnd());
                    hit.setCharStart(chunk.getCharStart()); hit.setCharEnd(chunk.getCharEnd());
                    hit.setBlockIds(Json.readStringList(chunk.getBlockIds())); hits.add(hit);
                }
                List<Map<String, Object>> attachedEvidence = persistEvidence(run, hits, "attachment_chunk");
                attachment.put("evidence", attachedEvidence);
                for (Map<String,Object> row : attachedEvidence) state.getEvidenceIds().add(String.valueOf(row.get("evidence_id")));
            }
            int budget = props.effectiveInputBudget();
            List<Map<String, Object>> base = ContextBuilder.baseMessages(history, summaryText, state,
                    "当前 Notebook 的可用资料由 list_notebook_sources 查询。", evidenceRows,
                    skillCatalog.summaries(), registry.summaries(NativeTools.ALWAYS_AVAILABLE), run.getQuery());
            List<Map<String,Object>> windowManifests = new ArrayList<>();
            List<List<Map<String, Object>>> packets = ContextBuilder.withAttachments(base, run.getQuery(),
                    attachmentPayloads, (int) (budget * 0.95), windowManifests);
            Map<String, Object> manifest = ContextBuilder.manifest(props.getModelContextWindow(), budget,
                    packets.stream().mapToInt(ContextBuilder::messagesTokens).max().orElse(0),
                    Map.of("base", ContextBuilder.messagesTokens(base)),
                    attachmentPayloads.stream().map(a -> {
                        Map<String, Object> meta = new LinkedHashMap<>(a); meta.remove("text"); meta.remove("evidence"); return meta;
                    }).toList(), List.of(), packets.size() > 1 ? "windowed" : "single");
            manifest.put("windows", packets.size());
            manifest.put("window_manifests", windowManifests);
            List<Map<String, Object>> manifests = messageList(stateMap.get("context_manifests"));
            manifests.add(manifest); stateMapPut(run, state, "context_manifests", manifests);
            events.emit(run.getId(), "context_manifest", manifest);

            Object pending = stateMap.get("pending_batch");
            String answer;
            if (pending instanceof Map<?, ?> pb) {
                answer = resumeBatch(run, state, registry, context, approvalMode, stateMap, run.getQuery());
                if (answer == null) {
                    return;
                }
            } else if (stateMap.get("working_messages") instanceof List<?> saved && !saved.isEmpty()) {
                answer = toolLoop(run, state, registry, context, messageList(saved), approvalMode);
            } else if (packets.size() == 1) {
                answer = toolLoop(run, state, registry, context, packets.get(0), approvalMode);
            } else {
                answer = windowedLoop(run, state, registry, context, base, packets, windowManifests, approvalMode, provider);
            }
            if (answer == null) {
                return;
            }
            TaskLease.check();
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                AgentRun current = runs.lockById(runId).orElseThrow();
                if (!Set.of("completed", "cancelled").contains(current.getStatus())) {
                    TaskLease.check();
                    finalizeAnswer(run, state, answer, evidenceRows);
                }
            });
        } catch (Exception e) {
            if (runs.findById(runId).map(r -> "cancelled".equals(r.getStatus())).orElse(false)) return;
            log.warn("agent run {} execution failed: {}", runId, e.getMessage());
            throw new IllegalStateException("agent execution failed: " + e.getMessage(), e);
        } finally {
            if (context != null && context.getRetrieval() != null) {
                try { context.getRetrieval().close(); } catch (Exception ignored) {}
            }
        }
    }

    public void recordFailure(String runId, String error, boolean retrying) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            AgentRun run = runs.lockById(runId).orElse(null);
            if (run == null || Set.of("completed", "cancelled").contains(run.getStatus())) return;
            run.setStatus(retrying ? "pending" : "failed");
            run.setErrorMessage(error);
            runs.save(run);
            if (!retrying) events.emit(runId, "run_failed", Map.of("message", error));
        });
    }

    private void persistRun(AgentRun run) {
        TaskLease.check();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            AgentRun current = runs.lockById(run.getId()).orElseThrow();
            if ("cancelled".equals(current.getStatus()) && !"cancelled".equals(run.getStatus())) {
                throw new IllegalStateException("run was cancelled");
            }
            TaskLease.check();
            runs.save(run);
        });
    }

    private String windowedLoop(AgentRun run, AgentState state, ToolRegistry registry, ToolContext context,
                                List<Map<String, Object>> base, List<List<Map<String, Object>>> packets,
                                List<Map<String,Object>> windowManifests, String approvalMode, DeepSeekProvider provider) {
        List<Map<String, Object>> windowResults = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            TaskLease.check();
            if ("cancelled".equals(runs.findById(run.getId()).orElse(run).getStatus())) return null;
            ProviderResponse response = provider.invoke(packets.get(i), List.of(), null);
            TaskLease.check();
            Map<String, Object> windowResult = new LinkedHashMap<>();
            windowResult.put("manifest", windowManifests.get(i));
            windowResult.put("conclusion", response.content());
            windowResults.add(windowResult);
            events.emit(run.getId(), "attachment_window_completed", Map.of("index", i + 1, "total", packets.size()));
        }
        windowResults = WindowReducer.reduce(run.getQuery(), windowResults, (int)(props.effectiveInputBudget() * .95), provider);
        List<Map<String, Object>> aggregate = new ArrayList<>(List.of(base.getFirst()));
        aggregate.add(Map.of("role", "user", "content",
                com.wangfugui.notebookagent.prompts.Prompts.aggregateWindowsPrompt(run.getQuery(), windowResults)));
        return toolLoop(run, state, registry, context, aggregate, approvalMode);
    }

    private ToolContext buildContext(AgentRun run, AgentState state, ToolRegistry registry) {
        ToolContext context = new ToolContext();
        context.setRunId(run.getId());
        context.setNotebookId(run.getNotebookId());
        context.setUserId(run.getUserId());
        context.setAllowedDocumentIds(allowedDocuments(run.getConversationId()));
        context.setState(state);
        context.setProps(props);
        context.setDocuments(documents);
        context.setVersions(versions);
        context.setChunks(chunks);
        context.setSections(sections);
        context.setTaskItems(taskItems);
        context.setExternalPapers(externalPapers);
        context.setOpenalex(new OpenAlexProvider(props));
        context.setSkills(skillCatalog);
        context.setMcp(mcp);
        context.setRegistry(registry);
        context.setSourceService(sourceService);
        context.setDeleteService(deleteService);
        context.setRetrieval(new RetrievalService(new MilvusStore(props), new Neo4jStore(props),
                new SiliconFlowProvider(props), new RagRouter(new DeepSeekProvider(props)), chunks, sections, versions, documents));
        return context;
    }

    private List<String> allowedDocuments(String conversationId) {
        List<ConversationResourceSelection> all = selections.findByConversationId(conversationId);
        if (all.isEmpty()) return null;
        return all.stream().filter(row -> !"off".equals(row.getMode())).map(ConversationResourceSelection::getDocumentId).toList();
    }

    private List<Map<String, Object>> history(AgentRun run, String excludeMessageId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Message message : messages.findByConversationIdAndRoleInOrderByCreatedAtAscIdAsc(
                run.getConversationId(), List.of("user", "assistant"))) {
            if (message.getId().equals(excludeMessageId)) {
                continue;
            }
            if ("assistant".equals(message.getRole()) && message.getContent().isBlank()) {
                continue;
            }
            out.add(new LinkedHashMap<>(Map.of("role", message.getRole(), "content", message.getContent())));
        }
        return out;
    }

    private String summary(AgentRun run) {
        return summaries.findFirstByConversationIdOrderByCreatedAtDesc(run.getConversationId())
                .map(ConversationSummary::getSummary)
                .map(Json::readMap)
                .map(m -> String.valueOf(m.getOrDefault("text", "")))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> attachments(AgentRun run) {
        List<String> ids = Json.readStringList(run.getAttachmentIds());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String documentId : ids) {
            Document document = documents.findByIdAndNotebookId(documentId, run.getNotebookId()).orElse(null);
            if (document == null || document.getActiveVersionId() == null) {
                continue;
            }
            DocumentVersion version = versions.findById(document.getActiveVersionId()).orElse(null);
            if (version == null || version.getFullText() == null) {
                continue;
            }
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("document_id", document.getId());
            attachment.put("document_version_id", version.getId());
            attachment.put("name", document.getTitle());
            attachment.put("text", version.getFullText());
            out.add(attachment);
        }
        return out;
    }

    private List<SearchHit> initialRagEvidence(ToolContext context, AgentRun run, AgentState state) {
        try {
            RagDecision decision = context.getRetrieval() == null ? null
                    : new RagRouter(new DeepSeekProvider(props)).route(run.getQuery());
            if (decision == null || !"rag".equals(decision.route())) {
                return List.of();
            }
            Map<String, Object> result = context.getRetrieval().search(run.getNotebookId(), run.getQuery(),
                    context.getAllowedDocumentIds(), "auto", 8, decision.graph());
            List<SearchHit> hits = new ArrayList<>();
            if (result.get("items") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof SearchHit hit) {
                        hits.add(hit);
                    }
                }
            }
            return hits;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> persistEvidence(AgentRun run, List<SearchHit> hits, String sourceKind) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SearchHit hit : hits) {
            Evidence row = evidence.findFirstByRunIdAndChunkIdAndSourceKind(run.getId(),hit.getChunkId(),sourceKind).orElseGet(Evidence::new);
            row.setRunId(run.getId());
            row.setChunkId(hit.getChunkId());
            row.setSourceKind(sourceKind);
            row.setSourceId(hit.getDocumentVersionId() == null ? hit.getChunkId() : hit.getDocumentVersionId());
            row.setText(hit.getText() == null ? "" : hit.getText());
            row.setSectionTitle(hit.getSectionTitle());
            row.setPageStart(hit.getPageStart());
            row.setPageEnd(hit.getPageEnd());
            row.setBlockIds(Json.write(hit.getBlockIds()));
            row.setRetrievalSources(Json.write(hit.getSources()));
            row.setScore(hit.getScore());
            row.setCharStart(hit.getCharStart()); row.setCharEnd(hit.getCharEnd());
            evidence.save(row);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidence_id", row.getId());
            map.put("text", row.getText()); map.put("chunk_id", row.getChunkId());
            map.put("source_kind", row.getSourceKind());
            map.put("source_id", row.getSourceId());
            map.put("section_title", row.getSectionTitle());
            map.put("page_start", row.getPageStart());
            map.put("page_end", row.getPageEnd());
            map.put("char_start", row.getCharStart());
            map.put("char_end", row.getCharEnd());
            rows.add(map);
        }
        return rows;
    }

    private String toolLoop(AgentRun run, AgentState state, ToolRegistry registry, ToolContext context,
                            List<Map<String, Object>> messages, String approvalMode) {
        List<Map<String, Object>> working = new ArrayList<>(messages);
        List<String> candidateNames = new ArrayList<>(NativeTools.ALWAYS_AVAILABLE);
        List<String> savedNames = Json.readStringList(Json.write(Json.readMap(run.getState()).getOrDefault("candidate_names", List.of())));
        candidateNames.addAll(savedNames);
        if (savedNames.isEmpty()) {
            if (toolRouter == null) toolRouter = new EmbeddingToolRouter(texts -> new SiliconFlowProvider(props).embed(texts));
            var catalog = registry.all().values().stream()
                    .filter(def -> !NativeTools.ALWAYS_AVAILABLE.contains(def.name()) && !state.getActiveToolAllowlist().contains(def.name())).toList();
            List<String> routed = new ArrayList<>(toolRouter.select(run.getQuery(), catalog, 10));
            for (ToolDefinition def : catalog) {
                if (routed.size() >= 10) break;
                if (!routed.contains(def.name())) routed.add(def.name());
            }
            candidateNames.addAll(routed);
        }
        candidateNames.addAll(state.getActiveToolAllowlist());
        candidateNames = new ArrayList<>(new LinkedHashSet<>(candidateNames));
        boolean[] cleaned = {false};
        DeepSeekProvider provider = new DeepSeekProvider(props);
        int budget = (int) (props.effectiveInputBudget() * 0.95);
        for (int round = state.getRoundNo(); round < MAX_ROUNDS; round++) {
            TaskLease.check();
            state.setRoundNo(round + 1);
            AgentRun current = runs.findById(run.getId()).orElse(run);
            if ("cancelled".equals(current.getStatus())) {
                state.setCancelled(true);
                return null;
            }
            for (String name : state.getActiveToolAllowlist()) {
                if (!candidateNames.contains(name)) {
                    candidateNames.add(name);
                }
            }
            candidateNames.removeIf(n -> !registry.all().containsKey(n));
            stateMapPut(run, state, "candidate_names", candidateNames);
            List<Map<String, Object>> schemas = registry.schemas(candidateNames);
            working = Compactor.compact(Compactor.sanitize(working), Math.max(6, props.getContextKeepRecentTurns()),
                    props.getContextKeepRecentToolResults(), cleaned[0], budget - ContextBuilder.estimateTokens(Json.write(schemas)), provider, cleaned);
            stateMapPut(run, state, "working_messages", working);
            ProviderResponse response = provider.invoke(working,
                    schemas.isEmpty() ? List.of() : schemas, delta -> events.publishTransient(run.getId(), "text_delta",
                            Map.of("delta", delta)));
            if (response.toolCalls().isEmpty()) {
                return response.content();
            }
            List<Map<String, Object>> assistantCalls = new ArrayList<>();
            for (ProviderToolCall call : response.toolCalls()) {
                String canonical = registry.resolveProviderName(call.name());
                ToolDefinition def = registry.get(canonical);
                String risk = def == null ? "write" : def.risk();
                ToolCall toolCall = new ToolCall();
                toolCall.setRunId(run.getId());
                toolCall.setProviderCallId(call.id());
                toolCall.setToolName(canonical);
                toolCall.setArguments(Json.write(call.arguments()));
                toolCall.setArgumentsHash(ToolRegistry.argumentsHash(canonical, call.arguments()));
                toolCall.setRisk(risk);
                toolCalls.save(toolCall);
                Map<String, Object> providerCall = new LinkedHashMap<>();
                providerCall.put("id", call.id());
                providerCall.put("type", "function");
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", Json.write(call.arguments()));
                providerCall.put("function", function);
                assistantCalls.add(providerCall);
                events.emit(run.getId(), "tool_call", Map.of(
                        "name", canonical, "arguments", call.arguments(), "risk", risk));
            }
            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response.content() == null ? "" : response.content());
            assistantMessage.put("tool_calls", assistantCalls);
            working.add(assistantMessage);
            List<Map<String, Object>> batchCalls = response.toolCalls().stream().map(c -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", c.id()); row.put("name", c.name()); row.put("arguments", c.arguments());
                return row;
            }).toList();
            if (!executeBatch(run, state, registry, context, working, batchCalls, 0, approvalMode)) return null;
        }
        throw new RuntimeException("maximum agent rounds exceeded");
    }

    private List<Map<String, Object>> messageList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) for (Object row : list) out.add(Json.readMap(Json.write(row)));
        return out;
    }

    private String resumeBatch(AgentRun run, AgentState state, ToolRegistry registry, ToolContext context,
                               String approvalMode, Map<String, Object> stateMap, String query) {
        Map<String, Object> pending = Json.readMap(Json.write(stateMap.get("pending_batch")));
        List<Map<String, Object>> working = messageList(stateMap.get("working_messages"));
        List<Map<String, Object>> calls = messageList(pending.get("calls"));
        if (calls.isEmpty()) {
            // Compatibility with checkpoints produced by the initial OpenCode version.
            for (int i = working.size() - 1; i >= 0; i--) {
                if (working.get(i).get("tool_calls") instanceof List<?> saved) {
                    for (Map<String, Object> call : messageList(saved)) {
                        Map<String, Object> fn = Json.readMap(Json.write(call.get("function")));
                        calls.add(Map.of("id", call.get("id"), "name", fn.get("name"),
                                "arguments", Json.readMap(String.valueOf(fn.get("arguments")))));
                    }
                    break;
                }
            }
        }
        int index = pending.get("index") instanceof Number n ? n.intValue() : 0;
        if (!executeBatch(run, state, registry, context, working, calls, index, approvalMode)) return null;
        return toolLoop(run, state, registry, context, working, approvalMode);
    }

    private boolean executeBatch(AgentRun run, AgentState state, ToolRegistry registry, ToolContext context,
                                 List<Map<String, Object>> working, List<Map<String, Object>> calls,
                                 int start, String approvalMode) {
        for (int i = start; i < calls.size(); i++) {
            TaskLease.check();
            AgentRun current = runs.findById(run.getId()).orElseThrow();
            if ("cancelled".equals(current.getStatus())) return false;
            Map<String, Object> call = calls.get(i);
            String id = String.valueOf(call.get("id"));
            String canonical = registry.resolveProviderName(String.valueOf(call.get("name")));
            Map<String, Object> args = Json.readMap(Json.write(call.get("arguments")));
            checkpoint(run, state, working, calls, i, id, approvalMode);
            ToolCall row = toolCalls.findByRunIdAndProviderCallId(run.getId(), id).orElse(null);
            Map<String, Object> observation;
            if (row != null && row.getResult() != null && Set.of("completed", "failed", "rejected").contains(row.getStatus())) {
                observation = Json.readMap(row.getResult());
            } else if (i >= MAX_EXECUTIONS_PER_ROUND) {
                observation = ToolObservation.error(canonical, "tool_call_limit_exceeded", "本轮工具调用达到上限").asPayload();
                updateToolCall(run.getId(), id, "failed", observation, null);
            } else if (registry.argumentsError(canonical, args) != null) {
                observation = ToolObservation.error(canonical, "invalid_arguments", registry.argumentsError(canonical, args)).asPayload();
                updateToolCall(run.getId(), id, "failed", observation, null);
            } else {
                String decision = registry.decide(canonical, approvalMode);
                ApprovalRequest approval = row == null ? null : approvals.findByRunId(run.getId()).stream()
                        .filter(a -> row.getId().equals(a.getToolCallId())).findFirst().orElse(null);
                if ("approve".equals(decision) && approval == null) {
                    if (row == null) throw new IllegalStateException("missing durable tool call");
                    approval = new ApprovalRequest();
                    approval.setRunId(run.getId()); approval.setToolCallId(row.getId());
                    approval.setReason("工具 " + canonical + " 需要人工确认"); approval.setMode(approvalMode);
                    approval.setToolRisk(row.getRisk()); approvals.save(approval);
                    events.emit(run.getId(), "approval_required", Map.of("approval_id", approval.getId(),
                            "tool", canonical, "risk", approval.getToolRisk(), "mode", approvalMode, "reason", approval.getReason()));
                }
                if (approval != null && "pending".equals(approval.getStatus())) {
                    run.setStatus("waiting_approval"); persistRun(run); return false;
                }
                if ("deny".equals(decision) || approval != null && "rejected".equals(approval.getStatus())) {
                    observation = ToolObservation.error(canonical,
                            approval == null ? "permission_denied" : "approval_rejected", "工具执行未获授权").asPayload();
                    updateToolCall(run.getId(), id, "rejected", observation, null);
                } else {
                    ToolObservation result = executeTool(registry, canonical, args, context);
                    observation = result.asPayload();
                    TaskLease.check();
                    updateToolCall(run.getId(), id, result.ok() ? "completed" : "failed", observation, null);
                }
                if (approval != null) events.emit(run.getId(), "approval_resolved", Map.of("decision", approval.getStatus()));
            }
            boolean present = working.stream().anyMatch(m -> "tool".equals(m.get("role")) && id.equals(m.get("tool_call_id")));
            if (!present) working.add(toolMessage(id, observation));
            events.emit(run.getId(), "tool_result", merge("tool", canonical, observation));
            if ("tool_search".equals(canonical) && Boolean.TRUE.equals(observation.get("ok"))) {
                Map<String,Object> content = Json.readMap(Json.write(observation.get("content")));
                List<String> discovered = messageList(content.get("tools")).stream().map(t -> String.valueOf(t.get("name"))).toList();
                stateMapPut(run, state, "candidate_names", discovered);
                state.setActiveToolAllowlist(new ArrayList<>(discovered));
            }
            checkpoint(run, state, working, calls, i + 1, id, approvalMode);
        }
        Map<String, Object> stateMap = Json.readMap(run.getState());
        stateMap.remove("pending_batch"); stateMap.put("working_messages", working);
        stateMap.put("agent_state", state.toMap());
        run.setState(Json.write(stateMap)); run.setStatus("running"); persistRun(run);
        saveTranscript(run, working);
        return true;
    }

    private void checkpoint(AgentRun run, AgentState state, List<Map<String, Object>> working,
                            List<Map<String, Object>> calls, int index, String id, String mode) {
        Map<String, Object> stateMap = Json.readMap(run.getState());
        stateMap.put("agent_state", state.toMap());
        stateMap.put("pending_batch", Map.of("calls", calls, "index", index, "call_id", id));
        stateMap.put("working_messages", working); stateMap.put("approval_mode", mode);
        run.setState(Json.write(stateMap)); persistRun(run);
    }

    private ToolObservation executeTool(ToolRegistry registry, String canonical, Map<String, Object> arguments,
                                        ToolContext context) {
        ToolDefinition def = registry.get(canonical);
        if (def == null || def.executor() == null) {
            return ToolObservation.error(canonical, "tool_not_found", "unknown tool: " + canonical);
        }
        try {
            TaskLease.check();
            ToolObservation result = def.executor().execute(arguments == null ? Map.of() : arguments, context);
            if (result.ok() && Set.of("search_notebook", "get_notebook_items").contains(canonical)) {
                Map<String, Object> content = new LinkedHashMap<>(result.content());
                List<Map<String, Object>> items = messageList(content.get("items"));
                for (Map<String, Object> item : items) {
                    if (item.get("chunk_id") == null) continue;
                    String chunkId = String.valueOf(item.get("chunk_id"));
                    Evidence row = evidence.findFirstByRunIdAndChunkIdAndSourceKind(context.getRunId(), chunkId, "notebook_chunk").orElseGet(Evidence::new);
                    row.setRunId(context.getRunId()); row.setChunkId(chunkId); row.setSourceKind("notebook_chunk");
                    row.setSourceId(String.valueOf(item.getOrDefault("document_version_id", chunkId)));
                    row.setText(String.valueOf(item.getOrDefault("text", item.getOrDefault("content", ""))));
                    row.setSectionTitle(str(item.get("section_title")));
                    row.setPageStart(integer(item.get("page_start"))); row.setPageEnd(integer(item.get("page_end")));
                    row.setCharStart(integer(item.get("char_start"))); row.setCharEnd(integer(item.get("char_end")));
                    row.setBlockIds(Json.write(item.getOrDefault("block_ids", List.of())));
                    row.setRetrievalSources(Json.write(item.getOrDefault("sources", List.of(canonical))));
                    row.setScore(item.get("score") instanceof Number n ? n.doubleValue() : null);
                    evidence.save(row); item.put("evidence_id", row.getId());
                    if (!context.getState().getEvidenceIds().contains(row.getId())) context.getState().getEvidenceIds().add(row.getId());
                }
                content.put("items", items); result = ToolObservation.ok(canonical, content);
            }
            return result;
        } catch (Exception e) {
            return ToolObservation.error(canonical, "tool_error", String.valueOf(e.getMessage()));
        }
    }

    private void updateToolCall(String runId, String providerCallId, String status, Map<String, Object> result,
                                String error) {
        toolCalls.findByRunIdAndProviderCallId(runId, providerCallId).ifPresent(tc -> {
            tc.setStatus(status);
            tc.setResult(Json.write(result));
            tc.setErrorMessage(error);
            toolCalls.save(tc);
        });
    }

    private Map<String, Object> toolMessage(String callId, Map<String, Object> observation) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", callId);
        message.put("content", Json.write(observation));
        return message;
    }

    private Map<String, Object> merge(String key, String tool, Map<String, Object> observation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, tool);
        map.putAll(observation);
        return map;
    }

    private void finalizeAnswer(AgentRun run, AgentState state, String answer, List<Map<String, Object>> initialEvidence) {
        Set<String> validIds = new LinkedHashSet<>();
        for (Evidence row : evidence.findByRunId(run.getId())) {
            validIds.add(row.getId());
        }
        for (Map<String, Object> row : initialEvidence) {
            if (row.get("evidence_id") != null) {
                validIds.add(String.valueOf(row.get("evidence_id")));
            }
        }
        Matcher matcher = EVIDENCE.matcher(answer);
        StringBuilder replaced = new StringBuilder();
        List<String> cited = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            replaced.append(answer, last, matcher.start());
            String id = matcher.group(1);
            if (validIds.contains(id)) {
                replaced.append(matcher.group());
                cited.add(id);
            } else {
                replaced.append("[无效引用已移除]");
            }
            last = matcher.end();
        }
        replaced.append(answer.substring(last));

        Message message = new Message();
        message.setConversationId(run.getConversationId());
        message.setRole("assistant");
        message.setContent(replaced.toString());
        messages.save(message);
        for (String id : new LinkedHashSet<>(cited)) {
            com.wangfugui.notebookagent.domain.Citation citation = new com.wangfugui.notebookagent.domain.Citation();
            citation.setMessageId(message.getId());
            citation.setEvidenceId(id);
            citation.setClaimText("Referenced by the final answer");
            citations.save(citation);
        }

        state.setEvidenceIds(new ArrayList<>(validIds));
        run.setStatus("completed");
        Map<String, Object> stateMap = Json.readMap(run.getState());
        stateMap.put("agent_state", state.toMap());
        stateMap.put("final_answer", replaced.toString());
        stateMap.remove("pending_batch"); stateMap.remove("working_messages");
        run.setState(Json.write(stateMap));
        runs.save(run);

        List<Map<String, Object>> citationPayload = new ArrayList<>();
        for (Evidence row : evidence.findByIdInAndRunId(cited, run.getId())) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("evidence_id", row.getId());
            map.put("source_kind", row.getSourceKind());
            map.put("source_id", row.getSourceId());
            map.put("section_title", row.getSectionTitle());
            map.put("page_start", row.getPageStart());
            map.put("page_end", row.getPageEnd());
            map.put("char_start", row.getCharStart());
            map.put("char_end", row.getCharEnd());
            citationPayload.add(map);
        }
        events.emit(run.getId(), "run_completed", Map.of("answer", replaced.toString(), "citations", citationPayload));
    }

    private void saveTranscript(AgentRun run, List<Map<String, Object>> working) {
        RunTranscript transcript = transcripts.findById(run.getId()).orElseGet(() -> {
            RunTranscript t = new RunTranscript();
            t.setId(run.getId());
            t.setConversationId(run.getConversationId());
            return t;
        });
        transcript.setMessages(Json.write(working));
        transcripts.save(transcript);
    }

    private void stateMapPut(AgentRun run, AgentState state, String key, Object value) {
        Map<String, Object> stateMap = Json.readMap(run.getState());
        stateMap.put("agent_state", state.toMap());
        stateMap.put(key, value);
        run.setState(Json.write(stateMap));
        persistRun(run);
    }

    private static Integer integer(Object value) { return value instanceof Number n ? n.intValue() : null; }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
