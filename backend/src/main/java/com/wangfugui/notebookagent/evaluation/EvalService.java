package com.wangfugui.notebookagent.evaluation;

import com.wangfugui.notebookagent.agent.AgentRuntime;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.Conversation;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.EvalCase;
import com.wangfugui.notebookagent.domain.EvalCaseResult;
import com.wangfugui.notebookagent.domain.EvalDataset;
import com.wangfugui.notebookagent.domain.EvalRun;
import com.wangfugui.notebookagent.domain.Evidence;
import com.wangfugui.notebookagent.domain.Message;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.CitationRepository;
import com.wangfugui.notebookagent.repository.ConversationRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.EvalCaseRepository;
import com.wangfugui.notebookagent.repository.EvalCaseResultRepository;
import com.wangfugui.notebookagent.repository.EvalDatasetRepository;
import com.wangfugui.notebookagent.repository.EvalRunRepository;
import com.wangfugui.notebookagent.repository.EvidenceRepository;
import com.wangfugui.notebookagent.repository.MessageRepository;
import com.wangfugui.notebookagent.repository.ToolCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluation snapshot creation, execution and metrics aggregation. */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final EvalDatasetRepository datasets;
    private final EvalCaseRepository cases;
    private final EvalRunRepository runs;
    private final EvalCaseResultRepository results;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AgentRunRepository agentRuns;
    private final EvidenceRepository evidence;
    private final CitationRepository citations;
    private final ChunkRepository chunks;
    private final DocumentVersionRepository versions;
    private final DocumentRepository documents;
    private final ToolCallRepository toolCalls;
    private final AgentRuntime runtime;

    public EvalService(EvalDatasetRepository datasets, EvalCaseRepository cases, EvalRunRepository runs,
                       EvalCaseResultRepository results, ConversationRepository conversations,
                       MessageRepository messages, AgentRunRepository agentRuns, EvidenceRepository evidence,
                       CitationRepository citations, ChunkRepository chunks, DocumentVersionRepository versions,
                       DocumentRepository documents, ToolCallRepository toolCalls, AgentRuntime runtime) {
        this.datasets = datasets;
        this.cases = cases;
        this.runs = runs;
        this.results = results;
        this.conversations = conversations;
        this.messages = messages;
        this.agentRuns = agentRuns;
        this.evidence = evidence;
        this.citations = citations;
        this.chunks = chunks;
        this.versions = versions;
        this.documents = documents;
        this.toolCalls = toolCalls;
        this.runtime = runtime;
    }

    public boolean datasetHasMetrics(List<EvalCase> caseList) {
        for (EvalCase c : caseList) {
            if (!Json.readStringList(c.getExpectedTools()).isEmpty()
                    || !Json.readStringList(c.getExpectedSourceTitles()).isEmpty()
                    || !Json.readStringList(c.getRequiredKeywords()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public List<String> notebookSourceTitles(String notebookId) {
        Set<String> titles = new LinkedHashSet<>();
        for (Document document : documents.findByNotebookId(notebookId)) {
            if (document.getActiveVersionId() != null) {
                titles.add(document.getTitle());
            }
        }
        return new ArrayList<>(titles);
    }

    public List<Map<String, Object>> missingSourcesFor(List<EvalCase> caseList, List<String> titles) {
        List<Map<String, Object>> missing = new ArrayList<>();
        for (EvalCase c : caseList) {
            List<String> expected = Json.readStringList(c.getExpectedSourceTitles());
            List<String> absent = new ArrayList<>();
            for (String title : expected) {
                boolean found = titles.stream().anyMatch(t -> t.toLowerCase().contains(title.toLowerCase())
                        || title.toLowerCase().contains(t.toLowerCase()));
                if (!found) {
                    absent.add(title);
                }
            }
            if (!absent.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("case_key", c.getCaseKey());
                row.put("question", c.getQuestion());
                row.put("missing", absent);
                missing.add(row);
            }
        }
        return missing;
    }

    @org.springframework.transaction.annotation.Transactional
    public EvalRun createRunSnapshot(String userId, String notebookId, EvalDataset dataset, List<EvalCase> caseList,
                                     Map<String, List<String>> missingMap, Map<String, Double> scoreConfig) {
        EvalRun run = new EvalRun();
        run.setUserId(userId);
        run.setNotebookId(notebookId);
        run.setDatasetId(dataset == null ? null : dataset.getId());
        run.setDatasetName(dataset == null ? "" : dataset.getName());
        run.setStatus("pending");
        run.setTotalCases(caseList.size());
        run.setScoreConfig(Json.write(scoreConfig));
        run.setMetrics("{}");
        runs.save(run);

        int ordinal = 1;
        for (EvalCase c : caseList) {
            EvalCaseResult result = new EvalCaseResult();
            result.setEvalRunId(run.getId());
            result.setCaseKey(c.getCaseKey() == null || c.getCaseKey().isBlank() ? "case-" + ordinal : c.getCaseKey());
            result.setOrdinal(ordinal++);
            result.setQuestion(c.getQuestion());
            result.setStatus("pending");
            result.setExpectedTools(c.getExpectedTools());
            result.setExpectedSourceTitles(c.getExpectedSourceTitles());
            result.setRequiredKeywords(c.getRequiredKeywords());
            result.setMissingSources(Json.write(missingMap.getOrDefault(result.getCaseKey(), List.of())));
            results.save(result);
        }
        return run;
    }

    public void executeEvaluation(String evalRunId) {
        EvalRun run = runs.findById(evalRunId).orElse(null);
        if (run == null || List.of("cancelled", "completed").contains(run.getStatus())) {
            return;
        }
        run.setStatus("running");
        runs.save(run);
        Map<String, Double> config = parseConfig(run.getScoreConfig());
        List<EvalCaseResult> caseResults = results.findByEvalRunIdOrderByOrdinalAsc(evalRunId);
        try {
            for (EvalCaseResult result : caseResults) {
                com.wangfugui.notebookagent.queue.TaskLease.check();
                if (List.of("passed", "failed", "error", "cancelled").contains(result.getStatus())) continue;
                EvalRun current = runs.findById(evalRunId).orElse(run);
                if ("cancelled".equals(current.getStatus())) {
                    if (List.of("pending", "running").contains(result.getStatus())) {
                        result.setStatus("cancelled");
                        results.save(result);
                    }
                    continue;
                }
                run.setCurrentCaseKey(result.getCaseKey());
                run.setCurrentCaseResultId(result.getId());
                runs.save(run);
                result.setStatus("running");
                results.save(result);

                long start = System.currentTimeMillis();
                try {
                    AgentRun agentRun = result.getAgentRunId() == null ? null : agentRuns.findById(result.getAgentRunId()).orElse(null);
                    if (agentRun == null) {
                    Conversation conversation = new Conversation();
                    conversation.setUserId(run.getUserId());
                    conversation.setNotebookId(run.getNotebookId());
                    String question = result.getQuestion();
                    conversation.setTitle(question.length() > 60 ? question.substring(0, 60) : question);
                    conversations.save(conversation);

                    Message message = new Message();
                    message.setConversationId(conversation.getId());
                    message.setRole("user");
                    message.setContent(question);
                    messages.save(message);

                    agentRun = new AgentRun();
                    agentRun.setConversationId(conversation.getId());
                    agentRun.setUserId(run.getUserId());
                    agentRun.setNotebookId(run.getNotebookId());
                    agentRun.setQuery(question);
                    agentRun.setStatus("pending");
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("input_message_id", message.getId());
                    state.put("approval_mode", "read_only");
                    agentRun.setState(Json.write(state));
                    agentRuns.save(agentRun);
                    run.setCurrentAgentRunId(agentRun.getId());
                    runs.save(run);
                    result.setAgentRunId(agentRun.getId()); results.save(result);
                    }
                    Conversation conversation = conversations.findById(agentRun.getConversationId()).orElseThrow();

                    runtime.execute(agentRun.getId());
                    AgentRun finished = agentRuns.findById(agentRun.getId()).orElse(agentRun);
                    long latency = System.currentTimeMillis() - start;
                    Map<String, Object> observation = collectObservation(finished, conversation.getId());
                    Map<String, Object> score = Scorers.scoreCase(
                            Json.readStringList(result.getExpectedTools()),
                            cast(observation.get("tools")),
                            Json.readStringList(result.getExpectedSourceTitles()),
                            cast(observation.get("evidence_titles")),
                            cast(observation.get("citation_titles")),
                            Json.readStringList(result.getMissingSources()),
                            Json.readStringList(result.getRequiredKeywords()),
                            observation.get("answer") == null ? null : String.valueOf(observation.get("answer")),
                            String.valueOf(observation.get("run_status")),
                            config);
                    applyScore(result, observation, score, agentRun.getId(), latency);
                } catch (Exception e) {
                    com.wangfugui.notebookagent.queue.TaskLease.check();
                    if (result.getAgentRunId() != null) runtime.recordFailure(result.getAgentRunId(), String.valueOf(e.getMessage()), false);
                    log.warn("eval case {} failed: {}", result.getCaseKey(), e.getMessage());
                    result.setStatus("error");
                    result.setPassed(false);
                    result.setErrorMessage(String.valueOf(e.getMessage()));
                    results.save(result);
                }
                run = runs.findById(evalRunId).orElse(run);
                if ("cancelled".equals(run.getStatus())) continue;
                List<EvalCaseResult> finishedResults = results.findByEvalRunIdOrderByOrdinalAsc(evalRunId);
                run.setCompletedCases((int) finishedResults.stream().filter(r -> List.of("passed","failed","error","cancelled").contains(r.getStatus())).count());
                run.setPassedCases((int) finishedResults.stream().filter(EvalCaseResult::isPassed).count());
                runs.save(run);
            }
            com.wangfugui.notebookagent.queue.TaskLease.check();
            run = runs.findById(evalRunId).orElseThrow();
            if ("cancelled".equals(run.getStatus())) return;
            run.setStatus("completed");
            run.setCurrentCaseKey(null);
            run.setCurrentAgentRunId(null);
            run.setCurrentCaseResultId(null);
            run.setMetrics(Json.write(aggregateMetrics(results.findByEvalRunIdOrderByOrdinalAsc(evalRunId))));
            runs.save(run);
        } catch (Exception e) {
            com.wangfugui.notebookagent.queue.TaskLease.check();
            run.setStatus("failed");
            run.setErrorMessage(String.valueOf(e.getMessage()));
            runs.save(run);
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> collectObservation(AgentRun run, String conversationId) {
        Map<String, Object> state = Json.readMap(run.getState());
        Object answer = state.get("final_answer");
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("answer", answer);
        observation.put("run_status", run.getStatus());
        List<String> tools = toolCalls.findByRunId(run.getId()).stream()
                .map(tc -> tc.getToolName()).distinct().sorted().toList();
        observation.put("tools", tools);
        List<String> evidenceTitles = new ArrayList<>();
        for (Evidence ev : evidence.findByRunId(run.getId())) {
            if (ev.getChunkId() != null) {
                Chunk chunk = chunks.findById(ev.getChunkId()).orElse(null);
                if (chunk != null) {
                    DocumentVersion version = versions.findById(chunk.getDocumentVersionId()).orElse(null);
                    if (version != null) {
                        documents.findById(version.getDocumentId()).map(Document::getTitle).ifPresent(evidenceTitles::add);
                    }
                }
            }
        }
        observation.put("evidence_titles", evidenceTitles);
        List<String> citationTitles = new ArrayList<>();
        for (Message message : messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)) {
            if (!"assistant".equals(message.getRole())) {
                continue;
            }
            for (var citation : citations.findByMessageId(message.getId())) {
                Evidence ev = evidence.findById(citation.getEvidenceId()).orElse(null);
                if (ev != null && ev.getChunkId() != null) {
                    Chunk chunk = chunks.findById(ev.getChunkId()).orElse(null);
                    if (chunk != null) {
                        DocumentVersion version = versions.findById(chunk.getDocumentVersionId()).orElse(null);
                        if (version != null) {
                            documents.findById(version.getDocumentId()).map(Document::getTitle).ifPresent(citationTitles::add);
                        }
                    }
                }
            }
        }
        observation.put("citation_titles", citationTitles);
        return observation;
    }

    @SuppressWarnings("unchecked")
    private void applyScore(EvalCaseResult result, Map<String, Object> observation, Map<String, Object> score,
                            String agentRunId, long latency) {
        result.setActualTools(Json.write(observation.get("tools")));
        result.setAnswer((String) observation.get("answer"));
        result.setAgentRunId(agentRunId);
        result.setLatencyMs((int) latency);
        result.setRouteScore((Double) score.get("route_score"));
        result.setRetrievalScore((Double) score.get("retrieval_score"));
        result.setCitationScore((Double) score.get("citation_score"));
        result.setKeywordScore((Double) score.get("keyword_score"));
        result.setTotalScore((Double) score.get("total_score"));
        result.setPassed(Boolean.TRUE.equals(score.get("passed")));
        result.setStatus(String.valueOf(score.get("status")));
        Map<String, Object> detail = (Map<String, Object>) score.get("detail");
        result.setMatchedSources(Json.write(detail.get("matched_sources")));
        result.setMatchedKeywords(Json.write(detail.get("matched_keywords")));
        result.setDetail(Json.write(detail));
        results.save(result);
    }

    public Map<String, Object> aggregateMetrics(List<EvalCaseResult> caseResults) {
        int total = caseResults.size();
        int completed = 0;
        int passed = 0;
        List<Double> route = new ArrayList<>();
        List<Double> retrieval = new ArrayList<>();
        List<Double> citation = new ArrayList<>();
        List<Double> keyword = new ArrayList<>();
        List<Double> totals = new ArrayList<>();
        List<Integer> latencies = new ArrayList<>();
        for (EvalCaseResult result : caseResults) {
            if (List.of("passed", "failed").contains(result.getStatus())) {
                completed++;
            }
            if (Boolean.TRUE.equals(result.isPassed())) {
                passed++;
            }
            if (result.getRouteScore() != null && !Json.readStringList(result.getExpectedTools()).isEmpty()) {
                route.add(result.getRouteScore());
            }
            if (result.getRetrievalScore() != null && !Json.readStringList(result.getExpectedSourceTitles()).isEmpty()) {
                retrieval.add(result.getRetrievalScore());
            }
            if (result.getCitationScore() != null && !Json.readStringList(result.getExpectedSourceTitles()).isEmpty()) {
                citation.add(result.getCitationScore());
            }
            if (result.getKeywordScore() != null && !Json.readStringList(result.getRequiredKeywords()).isEmpty()) {
                keyword.add(result.getKeywordScore());
            }
            if (result.getTotalScore() != null) {
                totals.add(result.getTotalScore());
            }
            if (result.getLatencyMs() != null) {
                latencies.add(result.getLatencyMs());
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total_cases", total);
        metrics.put("completed_cases", completed);
        metrics.put("passed_cases", passed);
        metrics.put("pass_rate", total == 0 ? null : round(passed / (double) total));
        metrics.put("route_accuracy", mean(route));
        metrics.put("retrieval_accuracy", mean(retrieval));
        metrics.put("citation_accuracy", mean(citation));
        metrics.put("keyword_coverage", mean(keyword));
        metrics.put("avg_total_score", mean(totals));
        metrics.put("avg_latency_ms", latencies.isEmpty() ? null
                : (int) Math.round(latencies.stream().mapToInt(Integer::intValue).average().orElse(0)));
        return metrics;
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        return round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> cast(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Map<String, Double> parseConfig(String json) {
        Map<String, Double> config = new LinkedHashMap<>(Scorers.DEFAULT_SCORE_CONFIG);
        for (Map.Entry<String, Object> e : Json.readMap(json).entrySet()) {
            if (e.getValue() instanceof Number n) {
                config.put(e.getKey(), n.doubleValue());
            }
        }
        return config;
    }
}
