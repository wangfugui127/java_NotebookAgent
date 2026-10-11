package com.wangfugui.notebookagent.evaluation;

import com.wangfugui.notebookagent.queue.TaskHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExecuteEvaluationTaskHandler implements TaskHandler {

    private final EvalService evalService;

    public ExecuteEvaluationTaskHandler(EvalService evalService) {
        this.evalService = evalService;
    }

    @Override
    public String type() {
        return "execute_evaluation";
    }

    @Override
    public void handle(String taskId, Map<String, Object> payload) {
        Object evalRunId = payload.get("eval_run_id");
        if (evalRunId != null) {
            evalService.executeEvaluation(String.valueOf(evalRunId));
        }
    }
}
