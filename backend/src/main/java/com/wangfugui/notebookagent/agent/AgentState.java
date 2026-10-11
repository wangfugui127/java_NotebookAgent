package com.wangfugui.notebookagent.agent;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-run scratch state serialized into agent_runs.state.agent_state. */
@Getter
@Setter
public class AgentState {
    private String runId;
    private String notebookId;
    private String userId;
    private int roundNo;
    private List<Map<String, Object>> tasks = new ArrayList<>();
    private List<String> evidenceIds = new ArrayList<>();
    private Map<String, Object> loadedSkills = new LinkedHashMap<>();
    private List<String> activeToolAllowlist = new ArrayList<>();
    private boolean graphDegraded;
    private boolean cancelled;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("run_id", runId);
        map.put("notebook_id", notebookId);
        map.put("user_id", userId);
        map.put("round_no", roundNo);
        map.put("tasks", tasks);
        map.put("evidence_ids", evidenceIds);
        map.put("loaded_skills", loadedSkills);
        map.put("active_tool_allowlist", activeToolAllowlist);
        map.put("graph_degraded", graphDegraded);
        map.put("cancelled", cancelled);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static AgentState fromMap(Map<String, Object> map) {
        AgentState state = new AgentState();
        state.runId = str(map.get("run_id"));
        state.notebookId = str(map.get("notebook_id"));
        state.userId = str(map.get("user_id"));
        state.roundNo = map.get("round_no") instanceof Number n ? n.intValue() : 0;
        if (map.get("tasks") instanceof List<?> t) {
            state.tasks = (List<Map<String, Object>>) t;
        }
        if (map.get("evidence_ids") instanceof List<?> e) {
            state.evidenceIds = new ArrayList<>(e.stream().map(String::valueOf).toList());
        }
        if (map.get("loaded_skills") instanceof Map<?, ?> s) {
            state.loadedSkills = (Map<String, Object>) s;
        }
        if (map.get("active_tool_allowlist") instanceof List<?> a) {
            state.activeToolAllowlist = new ArrayList<>(a.stream().map(String::valueOf).toList());
        }
        state.graphDegraded = Boolean.TRUE.equals(map.get("graph_degraded"));
        state.cancelled = Boolean.TRUE.equals(map.get("cancelled"));
        return state;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
