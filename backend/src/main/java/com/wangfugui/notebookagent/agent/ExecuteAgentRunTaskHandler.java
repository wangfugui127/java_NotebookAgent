package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.queue.TaskHandler;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Worker handler for execute_agent_run, gated by business status for idempotency. */
@Component
public class ExecuteAgentRunTaskHandler implements TaskHandler {

    private final AgentRuntime runtime;
    private final AgentRunRepository runs;

    public ExecuteAgentRunTaskHandler(AgentRuntime runtime, AgentRunRepository runs) {
        this.runtime = runtime;
        this.runs = runs;
    }

    @Override
    public String type() {
        return "execute_agent_run";
    }

    @Override
    public void handle(String taskId, Map<String, Object> payload) {
        Object runId = payload.get("run_id");
        if (runId == null) {
            return;
        }
        AgentRun run = runs.findById(String.valueOf(runId)).orElse(null);
        if (run == null) {
            return;
        }
        // The task's DB lease is already owned by this worker. running permits crash takeover.
        if (java.util.Set.of("completed", "cancelled").contains(run.getStatus())) {
            return;
        }
        runtime.execute(run.getId());
    }

    @Override
    public void onFailure(Map<String, Object> payload, String error, boolean retrying) {
        if (payload.get("run_id") != null) runtime.recordFailure(String.valueOf(payload.get("run_id")), error, retrying);
    }
}
