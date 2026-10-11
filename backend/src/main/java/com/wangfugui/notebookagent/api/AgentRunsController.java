package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.agent.EventStore;
import com.wangfugui.notebookagent.agent.RunService;
import com.wangfugui.notebookagent.agent.SseHub;
import com.wangfugui.notebookagent.api.dto.Dtos.AgentRunCreate;
import com.wangfugui.notebookagent.api.dto.Dtos.ApprovalResolve;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.domain.ApprovalRequest;
import com.wangfugui.notebookagent.domain.Conversation;
import com.wangfugui.notebookagent.domain.RunEvent;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.queue.TaskQueue;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.ApprovalRequestRepository;
import com.wangfugui.notebookagent.repository.ConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentRunsController {

    private static final Set<String> TERMINAL = Set.of("run_completed", "run_failed", "run_cancelled");
    private static final Set<String> TERMINAL_STATUS = Set.of("completed", "failed", "cancelled");

    private final AgentRunRepository runs;
    private final ConversationRepository conversations;
    private final ApprovalRequestRepository approvals;
    private final RunService runService;
    private final EventStore events;
    private final SseHub sseHub;
    private final TaskQueue tasks;
    private final ExecutorService sseExecutor;

    public AgentRunsController(AgentRunRepository runs, ConversationRepository conversations,
                               ApprovalRequestRepository approvals, RunService runService, EventStore events,
                               SseHub sseHub, TaskQueue tasks, ExecutorService sseExecutor) {
        this.runs = runs;
        this.conversations = conversations;
        this.approvals = approvals;
        this.runService = runService;
        this.events = events;
        this.sseHub = sseHub;
        this.tasks = tasks;
        this.sseExecutor = sseExecutor;
    }

    public static int resumeCursor(String lastEventId, int after) {
        try {
            int cursor = lastEventId == null || lastEventId.isBlank() ? after : Integer.parseInt(lastEventId.strip());
            if (cursor < 0) throw new NumberFormatException();
            return cursor;
        } catch (NumberFormatException e) {
            throw new ApiException(400, "invalid event cursor");
        }
    }

    private AgentRun owned(String runId, String userId) {
        return runs.findByIdAndUserId(runId, userId).orElseThrow(() -> ApiException.notFound("run not found"));
    }

    @PostMapping("/runs")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> createRun(@Valid @RequestBody AgentRunCreate body, @CurrentUser User user) {
        Conversation conversation = conversations.findByIdAndUserId(body.conversationId(), user.getId())
                .orElseThrow(() -> ApiException.notFound("conversation not found"));
        AgentRun run = runService.createRun(user, conversation, body.query(),
                body.attachmentIds() == null ? List.of() : body.attachmentIds(),
                body.attachmentInstructions() == null ? Map.of() : body.attachmentInstructions(),
                body.approvalMode());
        return view(run);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId, @CurrentUser User user) {
        return view(owned(runId, user.getId()));
    }

    @GetMapping("/runs/{runId}/context-manifest")
    public Map<String, Object> contextManifest(@PathVariable String runId, @CurrentUser User user) {
        AgentRun run = owned(runId, user.getId());
        Map<String, Object> state = Json.readMap(run.getState());
        Object manifests = state.getOrDefault("context_manifests", List.of());
        return Map.of("items", manifests);
    }

    @GetMapping("/runs/{runId}/events")
    public SseEmitter streamEvents(@PathVariable String runId,
                                   @RequestParam(value = "after", defaultValue = "0") int after,
                                   @org.springframework.web.bind.annotation.RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                   @CurrentUser User user) {
        final int resumeAfter = resumeCursor(lastEventId, after);
        owned(runId, user.getId());
        SseEmitter emitter = new SseEmitter(0L);
        SseHub.Subscription subscription = sseHub.subscribe(runId);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        sseExecutor.execute(() -> {
            int cursor = resumeAfter;
            try {
                for (RunEvent event : events.replay(runId, cursor)) {
                    send(emitter, event);
                    cursor = event.getSequence();
                    if (TERMINAL.contains(event.getEventType())) {
                        emitter.complete();
                        return;
                    }
                }
                long lastPoll = System.currentTimeMillis();
                while (!subscription.isClosed()) {
                    String message = subscription.poll(500);
                    if (message != null) {
                        Map<String, Object> envelope = Json.readMap(message);
                        Object sequence = envelope.get("sequence");
                        String type = String.valueOf(envelope.get("type"));
                        Object payload = envelope.get("payload");
                        if (sequence == null) {
                            emitter.send(SseEmitter.event().name(type).data(Json.write(payload)));
                        } else {
                            int seq = ((Number) sequence).intValue();
                            if (seq > cursor) {
                                // Pub/sub delivery can be reordered; the committed DB log owns ordering.
                                for (RunEvent durable : events.replay(runId, cursor)) {
                                    send(emitter,durable); cursor=durable.getSequence();
                                    if (TERMINAL.contains(durable.getEventType())) { emitter.complete(); return; }
                                }
                            }
                        }
                        lastPoll = System.currentTimeMillis();
                        continue;
                    }
                    if (System.currentTimeMillis() - lastPoll > 2000) {
                        lastPoll = System.currentTimeMillis();
                        for (RunEvent event : events.replay(runId, cursor)) {
                            send(emitter, event);
                            cursor = event.getSequence();
                            if (TERMINAL.contains(event.getEventType())) {
                                emitter.complete();
                                return;
                            }
                        }
                        AgentRun run = runs.findById(runId).orElse(null);
                        if (run == null || TERMINAL_STATUS.contains(run.getStatus())) {
                            emitter.complete();
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // client already gone
                }
            } finally {
                subscription.close();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, RunEvent event) throws java.io.IOException {
        emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence()))
                .name(event.getEventType()).data(event.getPayload()));
    }

    @PostMapping("/runs/{runId}/cancel")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> cancel(@PathVariable String runId, @CurrentUser User user) {
        AgentRun run = owned(runId, user.getId());
        run = runs.lockById(runId).orElseThrow(() -> ApiException.notFound("run not found"));
        run.setStatus("cancelled");
        runs.save(run);
        return Map.of("status", "cancelled");
    }

    @PostMapping("/runs/{runId}/retry")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> retry(@PathVariable String runId, @CurrentUser User user) {
        AgentRun run = owned(runId, user.getId());
        run = runs.lockById(runId).orElseThrow(() -> ApiException.notFound("run not found"));
        if (!Set.of("failed", "cancelled").contains(run.getStatus())) {
            throw ApiException.conflict("only failed or cancelled runs can be retried");
        }
        Map<String,Object> stateMap = Json.readMap(run.getState());
        Map<String,Object> agentState = Json.readMap(Json.write(stateMap.getOrDefault("agent_state",Map.of())));
        agentState.put("round_no",0); agentState.put("cancelled",false);
        stateMap.put("agent_state",agentState); run.setState(Json.write(stateMap));
        run.setStatus("pending");
        run.setErrorMessage(null);
        runs.save(run);
        tasks.enqueue("execute_agent_run", Map.of("run_id", run.getId()), "run:" + run.getId() + ":retry:"
                + System.nanoTime(), 3);
        return Map.of("status", "pending");
    }

    @GetMapping("/runs/{runId}/approvals")
    public Map<String, Object> listApprovals(@PathVariable String runId, @CurrentUser User user) {
        owned(runId, user.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApprovalRequest approval : approvals.findByRunId(runId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", approval.getId());
            row.put("status", approval.getStatus());
            row.put("reason", approval.getReason());
            row.put("mode", approval.getMode());
            row.put("tool_risk", approval.getToolRisk());
            row.put("tool_call_id", approval.getToolCallId());
            items.add(row);
        }
        return Map.of("items", items);
    }

    @PostMapping("/approvals/{approvalId}/resolve")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> resolveApproval(@PathVariable String approvalId,
                                               @Valid @RequestBody ApprovalResolve body, @CurrentUser User user) {
        if (!Set.of("approved", "rejected").contains(body.decision())) {
            throw new ApiException(422, "decision must be approved or rejected");
        }
        ApprovalRequest approval = approvals.lockById(approvalId).orElse(null);
        if (approval == null) {
            throw ApiException.notFound("approval not found");
        }
        AgentRun run = owned(approval.getRunId(), user.getId());
        if (TERMINAL_STATUS.contains(run.getStatus())) {
            throw ApiException.conflict("run is already terminal");
        }
        if (!"pending".equals(approval.getStatus())) {
            throw ApiException.conflict("approval already resolved");
        }
        approval.setStatus(body.decision());
        approval.setResolvedBy(user.getId());
        approvals.save(approval);
        tasks.enqueue("execute_agent_run", Map.of("run_id", run.getId()),
                "run:" + run.getId() + ":approval:" + approval.getId(), 3);
        return Map.of("status", body.decision());
    }

    private Map<String, Object> view(AgentRun run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.getId());
        map.put("conversation_id", run.getConversationId());
        map.put("status", run.getStatus());
        map.put("query", run.getQuery());
        map.put("error_message", run.getErrorMessage());
        map.put("created_at", run.getCreatedAt());
        return map;
    }
}
