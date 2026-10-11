package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.api.dto.Dtos.EvalCaseInput;
import com.wangfugui.notebookagent.api.dto.Dtos.EvalCaseUpdate;
import com.wangfugui.notebookagent.api.dto.Dtos.EvalDatasetCreate;
import com.wangfugui.notebookagent.api.dto.Dtos.EvalDatasetUpdate;
import com.wangfugui.notebookagent.api.dto.Dtos.EvalImportRequest;
import com.wangfugui.notebookagent.api.dto.Dtos.EvalRunCreate;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.domain.EvalCase;
import com.wangfugui.notebookagent.domain.EvalCaseResult;
import com.wangfugui.notebookagent.domain.EvalDataset;
import com.wangfugui.notebookagent.domain.EvalRun;
import com.wangfugui.notebookagent.domain.Notebook;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.evaluation.EvalService;
import com.wangfugui.notebookagent.evaluation.Scorers;
import com.wangfugui.notebookagent.queue.TaskQueue;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.EvalCaseRepository;
import com.wangfugui.notebookagent.repository.EvalCaseResultRepository;
import com.wangfugui.notebookagent.repository.EvalDatasetRepository;
import com.wangfugui.notebookagent.repository.EvalRunRepository;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/eval")
public class EvaluationsController {

    private final EvalDatasetRepository datasets;
    private final EvalCaseRepository cases;
    private final EvalRunRepository runs;
    private final EvalCaseResultRepository results;
    private final NotebookRepository notebooks;
    private final AgentRunRepository agentRuns;
    private final EvalService evalService;
    private final TaskQueue tasks;
    private final AppProperties props;

    public EvaluationsController(EvalDatasetRepository datasets, EvalCaseRepository cases, EvalRunRepository runs,
                                 EvalCaseResultRepository results, NotebookRepository notebooks,
                                 AgentRunRepository agentRuns, EvalService evalService, TaskQueue tasks,
                                 AppProperties props) {
        this.datasets = datasets;
        this.cases = cases;
        this.runs = runs;
        this.results = results;
        this.notebooks = notebooks;
        this.agentRuns = agentRuns;
        this.evalService = evalService;
        this.tasks = tasks;
        this.props = props;
    }

    private EvalDataset ownedDataset(String datasetId, String userId) {
        EvalDataset dataset = datasets.findById(datasetId).orElse(null);
        if (dataset == null || !dataset.getUserId().equals(userId)) {
            throw ApiException.notFound("dataset not found");
        }
        return dataset;
    }

    private EvalRun ownedRun(String runId, String userId) {
        return runs.findByIdAndUserId(runId, userId).orElseThrow(() -> ApiException.notFound("eval run not found"));
    }

    @GetMapping("/datasets")
    public Map<String, Object> listDatasets(@CurrentUser User user) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvalDataset dataset : datasets.findByUserIdOrderByCreatedAtDesc(user.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", dataset.getId());
            row.put("name", dataset.getName());
            row.put("description", dataset.getDescription());
            row.put("created_at", dataset.getCreatedAt());
            row.put("case_count", cases.countByDatasetId(dataset.getId()));
            runs.findFirstByDatasetIdAndUserIdOrderByCreatedAtDesc(dataset.getId(), user.getId()).ifPresentOrElse(latest -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", latest.getId());
                summary.put("status", latest.getStatus());
                summary.put("pass_rate", Json.readMap(latest.getMetrics()).get("pass_rate"));
                summary.put("created_at", latest.getCreatedAt());
                row.put("latest_run", summary);
            }, () -> row.put("latest_run", null));
            items.add(row);
        }
        return Map.of("items", items);
    }

    @PostMapping("/datasets")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDataset(@Valid @RequestBody EvalDatasetCreate body, @CurrentUser User user) {
        EvalDataset dataset = new EvalDataset();
        dataset.setUserId(user.getId());
        dataset.setName(body.name());
        dataset.setDescription(body.description() == null ? "" : body.description());
        datasets.save(dataset);
        return datasetView(dataset);
    }

    @PatchMapping("/datasets/{datasetId}")
    @Transactional
    public Map<String, Object> updateDataset(@PathVariable String datasetId, @Valid @RequestBody EvalDatasetUpdate body,
                                             @CurrentUser User user) {
        EvalDataset dataset = ownedDataset(datasetId, user.getId());
        if (body.name() != null) {
            dataset.setName(body.name());
        }
        if (body.description() != null) {
            dataset.setDescription(body.description());
        }
        datasets.save(dataset);
        return datasetView(dataset);
    }

    @DeleteMapping("/datasets/{datasetId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDataset(@PathVariable String datasetId, @CurrentUser User user) {
        ownedDataset(datasetId, user.getId());
        datasets.deleteById(datasetId);
    }

    @GetMapping("/datasets/{datasetId}")
    public Map<String, Object> datasetDetail(@PathVariable String datasetId, @CurrentUser User user) {
        EvalDataset dataset = ownedDataset(datasetId, user.getId());
        Map<String, Object> out = new LinkedHashMap<>(datasetView(dataset));
        List<Map<String, Object>> caseItems = new ArrayList<>();
        for (EvalCase c : cases.findByDatasetIdOrderByOrdinalAscCreatedAtAsc(datasetId)) {
            caseItems.add(caseView(c));
        }
        out.put("cases", caseItems);
        return out;
    }

    @PostMapping("/datasets/{datasetId}/cases")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createCase(@PathVariable String datasetId, @Valid @RequestBody EvalCaseInput body,
                                          @CurrentUser User user) {
        EvalDataset dataset = ownedDataset(datasetId, user.getId());
        int next = (int) cases.countByDatasetId(dataset.getId()) + 1;
        EvalCase c = new EvalCase();
        c.setDatasetId(dataset.getId());
        c.setCaseKey(uniqueKey(dataset.getId(), body.caseKey()));
        c.setQuestion(body.question());
        c.setExpectedTools(Json.write(body.expectedTools() == null ? List.of() : body.expectedTools()));
        c.setExpectedSourceTitles(Json.write(body.expectedSourceTitles() == null ? List.of() : body.expectedSourceTitles()));
        c.setRequiredKeywords(Json.write(body.requiredKeywords() == null ? List.of() : body.requiredKeywords()));
        c.setOrdinal(next);
        cases.save(c);
        return caseView(c);
    }

    @PatchMapping("/cases/{caseId}")
    @Transactional
    public Map<String, Object> updateCase(@PathVariable String caseId, @Valid @RequestBody EvalCaseUpdate body,
                                          @CurrentUser User user) {
        EvalCase c = ownedCase(caseId, user.getId());
        if (body.caseKey() != null && !body.caseKey().isBlank()) {
            c.setCaseKey(uniqueKey(c.getDatasetId(), body.caseKey()));
        }
        if (body.question() != null) {
            c.setQuestion(body.question());
        }
        if (body.expectedTools() != null) {
            c.setExpectedTools(Json.write(body.expectedTools()));
        }
        if (body.expectedSourceTitles() != null) {
            c.setExpectedSourceTitles(Json.write(body.expectedSourceTitles()));
        }
        if (body.requiredKeywords() != null) {
            c.setRequiredKeywords(Json.write(body.requiredKeywords()));
        }
        cases.save(c);
        return caseView(c);
    }

    @DeleteMapping("/cases/{caseId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCase(@PathVariable String caseId, @CurrentUser User user) {
        EvalCase c = ownedCase(caseId, user.getId());
        cases.deleteById(c.getId());
    }

    @PostMapping("/datasets/{datasetId}/import")
    @Transactional
    public Map<String, Integer> importCases(@PathVariable String datasetId, @Valid @RequestBody EvalImportRequest body,
                                            @CurrentUser User user) {
        EvalDataset dataset = ownedDataset(datasetId, user.getId());
        int total = insertCases(dataset, body.cases() == null ? List.of() : body.cases(), body.replace());
        return Map.of("case_count", total);
    }

    @PostMapping("/datasets/import-default")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> importDefault(@CurrentUser User user) {
        Map<String, Object> payload = readGolden();
        EvalDataset dataset = new EvalDataset();
        dataset.setUserId(user.getId());
        dataset.setName(String.valueOf(payload.getOrDefault("name", "示例评测集")));
        dataset.setDescription(String.valueOf(payload.getOrDefault("description", "示例题目，请按你的资料修改后再运行。")));
        datasets.save(dataset);
        List<EvalCaseInput> caseInputs = new ArrayList<>();
        if (payload.get("cases") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    caseInputs.add(new EvalCaseInput(str(m.get("case_key")), String.valueOf(m.get("question")),
                            strList(m.get("expected_tools")), strList(m.get("expected_source_titles")),
                            strList(m.get("required_keywords"))));
                }
            }
        }
        insertCases(dataset, caseInputs, false);
        return datasetView(dataset);
    }

    @PostMapping("/datasets/{datasetId}/runs")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> startRun(@PathVariable String datasetId, @Valid @RequestBody EvalRunCreate body,
                                        @CurrentUser User user) {
        EvalDataset dataset = ownedDataset(datasetId, user.getId());
        Notebook notebook = notebooks.findByIdAndOwnerId(body.notebookId(), user.getId())
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
        List<EvalCase> caseList = cases.findByDatasetIdOrderByOrdinalAscCreatedAtAsc(dataset.getId());
        if (caseList.isEmpty()) {
            throw ApiException.unprocessable("评测集还没有题目，请先添加题目");
        }
        if (!evalService.datasetHasMetrics(caseList)) {
            throw ApiException.unprocessable("评测集未配置任何评分维度：请为题目填写期望工具、期望来源或关键点");
        }
        List<String> titles = evalService.notebookSourceTitles(notebook.getId());
        List<Map<String, Object>> missing = evalService.missingSourcesFor(caseList, titles);
        if (!missing.isEmpty() && !body.allowMissing()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("message", "目标 Notebook 缺少部分预期文档，请补充文档或选择仍然开始。");
            detail.put("missing_sources", missing);
            throw new ApiException(409, detail);
        }
        Map<String, List<String>> missingMap = new LinkedHashMap<>();
        for (Map<String, Object> item : missing) {
            missingMap.put(String.valueOf(item.get("case_key")), strList(item.get("missing")));
        }
        EvalRun run = evalService.createRunSnapshot(user.getId(), notebook.getId(), dataset, caseList,
                missingMap, Scorers.DEFAULT_SCORE_CONFIG);
        tasks.enqueue("execute_evaluation", Map.of("eval_run_id", run.getId()), "eval:" + run.getId(), 1);
        return runView(run);
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns(@RequestParam(value = "notebook_id", required = false) String notebookId,
                                        @CurrentUser User user) {
        List<EvalRun> list = notebookId == null || notebookId.isBlank()
                ? runs.findByUserIdOrderByCreatedAtDesc(user.getId())
                : runs.findByUserIdAndNotebookIdOrderByCreatedAtDesc(user.getId(), notebookId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvalRun run : list) {
            items.add(runView(run));
        }
        return Map.of("items", items);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId, @CurrentUser User user) {
        return runView(ownedRun(runId, user.getId()));
    }

    @GetMapping("/runs/{runId}/results")
    public Map<String, Object> getResults(@PathVariable String runId, @CurrentUser User user) {
        EvalRun run = ownedRun(runId, user.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (EvalCaseResult result : results.findByEvalRunIdOrderByOrdinalAsc(runId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.getId());
            row.put("case_key", result.getCaseKey());
            row.put("ordinal", result.getOrdinal());
            row.put("question", result.getQuestion());
            row.put("status", result.getStatus());
            row.put("expected_tools", Json.readStringList(result.getExpectedTools()));
            row.put("actual_tools", Json.readStringList(result.getActualTools()));
            row.put("expected_source_titles", Json.readStringList(result.getExpectedSourceTitles()));
            row.put("matched_sources", Json.readStringList(result.getMatchedSources()));
            row.put("missing_sources", Json.readStringList(result.getMissingSources()));
            row.put("required_keywords", Json.readStringList(result.getRequiredKeywords()));
            row.put("matched_keywords", Json.readStringList(result.getMatchedKeywords()));
            row.put("answer", result.getAnswer());
            row.put("route_score", result.getRouteScore());
            row.put("retrieval_score", result.getRetrievalScore());
            row.put("citation_score", result.getCitationScore());
            row.put("keyword_score", result.getKeywordScore());
            row.put("total_score", result.getTotalScore());
            row.put("passed", result.isPassed());
            row.put("latency_ms", result.getLatencyMs());
            row.put("agent_run_id", result.getAgentRunId());
            row.put("error_message", result.getErrorMessage());
            row.put("detail", Json.readMap(result.getDetail()));
            items.add(row);
        }
        return Map.of("run", runView(run), "items", items);
    }

    @PostMapping("/runs/{runId}/cancel")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> cancelRun(@PathVariable String runId, @CurrentUser User user) {
        EvalRun run = ownedRun(runId, user.getId());
        if (List.of("completed", "failed", "cancelled").contains(run.getStatus())) {
            throw ApiException.conflict("evaluation already finished");
        }
        run.setStatus("cancelled");
        if (run.getCurrentAgentRunId() != null) {
            AgentRun agentRun = agentRuns.findById(run.getCurrentAgentRunId()).orElse(null);
            if (agentRun != null && agentRun.getUserId().equals(user.getId())) {
                agentRun.setStatus("cancelled");
                agentRuns.save(agentRun);
            }
        }
        runs.save(run);
        return Map.of("status", "cancelled");
    }

    // ------------------------------------------------------------------ helpers

    private int insertCases(EvalDataset dataset, List<EvalCaseInput> inputs, boolean replace) {
        if (replace) {
            for (EvalCase existing : cases.findByDatasetIdOrderByOrdinalAscCreatedAtAsc(dataset.getId())) {
                cases.deleteById(existing.getId());
            }
        }
        int existing = (int) cases.countByDatasetId(dataset.getId());
        int offset = 1;
        for (EvalCaseInput input : inputs) {
            EvalCase c = new EvalCase();
            c.setDatasetId(dataset.getId());
            c.setCaseKey(uniqueKey(dataset.getId(), input.caseKey()));
            c.setQuestion(input.question());
            c.setExpectedTools(Json.write(input.expectedTools() == null ? List.of() : input.expectedTools()));
            c.setExpectedSourceTitles(Json.write(input.expectedSourceTitles() == null ? List.of() : input.expectedSourceTitles()));
            c.setRequiredKeywords(Json.write(input.requiredKeywords() == null ? List.of() : input.requiredKeywords()));
            c.setOrdinal(existing + offset++);
            cases.save(c);
        }
        return existing + inputs.size();
    }

    private String uniqueKey(String datasetId, String base) {
        String stem = base == null || base.isBlank() ? "case-" + Long.toHexString(System.nanoTime()) : base.strip();
        List<String> existing = cases.findByDatasetIdOrderByOrdinalAscCreatedAtAsc(datasetId).stream()
                .map(EvalCase::getCaseKey).toList();
        if (!existing.contains(stem)) {
            return stem;
        }
        int index = 2;
        while (existing.contains(stem + "-" + index)) {
            index++;
        }
        return stem + "-" + index;
    }

    private EvalCase ownedCase(String caseId, String userId) {
        EvalCase c = cases.findById(caseId).orElse(null);
        if (c == null) {
            throw ApiException.notFound("case not found");
        }
        ownedDataset(c.getDatasetId(), userId);
        return c;
    }

    private Map<String, Object> readGolden() {
        Path path = Path.of(props.getEvalGoldenPath());
        if (!Files.exists(path)) {
            throw new ApiException(404, "golden sample not found at " + path);
        }
        try {
            return Json.readMap(Files.readString(path));
        } catch (Exception e) {
            throw ApiException.badRequest("invalid golden sample file: " + e.getMessage());
        }
    }

    private Map<String, Object> datasetView(EvalDataset dataset) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dataset.getId());
        map.put("name", dataset.getName());
        map.put("description", dataset.getDescription());
        map.put("created_at", dataset.getCreatedAt());
        return map;
    }

    private Map<String, Object> caseView(EvalCase c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("case_key", c.getCaseKey());
        map.put("question", c.getQuestion());
        map.put("expected_tools", Json.readStringList(c.getExpectedTools()));
        map.put("expected_source_titles", Json.readStringList(c.getExpectedSourceTitles()));
        map.put("required_keywords", Json.readStringList(c.getRequiredKeywords()));
        map.put("ordinal", c.getOrdinal());
        return map;
    }

    private Map<String, Object> runView(EvalRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.getId());
        map.put("notebook_id", run.getNotebookId());
        map.put("dataset_id", run.getDatasetId());
        map.put("dataset_name", run.getDatasetName());
        map.put("status", run.getStatus());
        map.put("total_cases", run.getTotalCases());
        map.put("completed_cases", run.getCompletedCases());
        map.put("passed_cases", run.getPassedCases());
        map.put("current_case_key", run.getCurrentCaseKey());
        map.put("metrics", Json.readMap(run.getMetrics()));
        map.put("error_message", run.getErrorMessage());
        map.put("created_at", run.getCreatedAt());
        return map;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
