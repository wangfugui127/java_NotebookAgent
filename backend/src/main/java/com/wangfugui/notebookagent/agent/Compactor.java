package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.prompts.Prompts;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import java.util.*;

/** Preserve complete tool-call pairs and never silently discard an over-budget recent turn. */
public final class Compactor {
    public static final String TOOL_RESULT_PLACEHOLDER = "{\"truncated\":true,\"note\":\"工具已执行，结果原文在数据库\"}";
    public static final String TOOL_INCOMPLETE = "{\"ok\":false,\"error_code\":\"tool_result_missing\"}";
    public static final String SUMMARY_PREFIX = "[较早对话摘要]";
    private static Map<String, Object> copy(Map<String, Object> m) { return Json.readMap(Json.write(m)); }
    private static void flush(List<Map<String, Object>> out, Set<String> pending) {
        for (String id : pending) out.add(new LinkedHashMap<>(Map.of("role", "tool", "tool_call_id", id, "content", TOOL_INCOMPLETE)));
        pending.clear();
    }
    public static List<Map<String, Object>> sanitize(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> pending = new LinkedHashSet<>();
        for (Map<String, Object> raw : messages) {
            Map<String, Object> m = copy(raw);
            m.keySet().removeIf(k -> !Set.of("role", "content", "tool_calls", "tool_call_id", "name").contains(k));
            String role = String.valueOf(m.get("role"));
            if ("tool".equals(role)) {
                String id = String.valueOf(m.get("tool_call_id"));
                if (pending.remove(id)) out.add(m);
                continue;
            }
            flush(out, pending); out.add(m);
            if ("assistant".equals(role) && m.get("tool_calls") instanceof List<?> calls) for (Object c : calls) {
                if (c instanceof Map<?, ?> call && call.get("id") != null) pending.add(String.valueOf(call.get("id")));
            }
        }
        flush(out, pending); return out;
    }
    public static List<Map<String, Object>> clearOldToolResults(List<Map<String, Object>> messages, int keep) {
        List<Map<String, Object>> out = messages.stream().map(Compactor::copy).toList();
        List<Map<String, Object>> tools = out.stream().filter(m -> "tool".equals(m.get("role"))).toList();
        for (int i = 0; i < Math.max(0, tools.size() - keep); i++) {
            if (!TOOL_INCOMPLETE.equals(tools.get(i).get("content"))) tools.get(i).put("content", TOOL_RESULT_PLACEHOLDER);
        }
        return new ArrayList<>(out);
    }
    public static List<Map<String, Object>> compact(List<Map<String, Object>> messages, int keepRecentTurns,
            boolean cleanupDone, int budget, DeepSeekProvider provider, boolean[] cleanedFlag) {
        return compact(messages, keepRecentTurns, 4, cleanupDone, budget, provider, cleanedFlag);
    }
    public static List<Map<String, Object>> compact(List<Map<String, Object>> messages, int keepRecentTurns, int keepResults,
            boolean cleanupDone, int budget, DeepSeekProvider provider, boolean[] cleanedFlag) {
        List<Map<String, Object>> working = new ArrayList<>(messages.stream().map(Compactor::copy).toList());
        if (ContextBuilder.messagesTokens(working) <= budget) return working;
        List<List<Map<String, Object>>> turns = turns(working.stream().filter(m -> !"system".equals(m.get("role"))).toList());
        int olderCount = Math.max(0, turns.size() - keepRecentTurns);
        Set<Map<String, Object>> recent = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = olderCount; i < turns.size(); i++) recent.addAll(turns.get(i));
        if (!cleanupDone) {
            List<Map<String, Object>> tools = working.stream().filter(m -> "tool".equals(m.get("role"))).toList();
            for (int i = 0; i < Math.max(0, tools.size() - keepResults); i++) {
                Map<String, Object> tool = tools.get(i);
                if (recent.contains(tool) || TOOL_RESULT_PLACEHOLDER.equals(tool.get("content")) || TOOL_INCOMPLETE.equals(tool.get("content"))) continue;
                tool.put("content", TOOL_RESULT_PLACEHOLDER); cleanedFlag[0] = true;
                if (ContextBuilder.messagesTokens(working) <= budget) return working;
            }
        }
        if (olderCount == 0 || provider == null) throw new IllegalStateException("context exceeds input budget without removable older turns");
        StringBuilder older = new StringBuilder();
        for (int i = 0; i < olderCount; i++) older.append(Json.write(turns.get(i))).append('\n');
        String summary = provider.invoke(List.of(
                Map.of("role", "system", "content", Prompts.CONTEXT_COMPACT_SYSTEM_PROMPT),
                Map.of("role", "user", "content", Prompts.contextCompactPrompt("", older.toString()))), List.of(), null).content();
        if (summary == null || summary.isBlank()) throw new IllegalStateException("context summary failed");
        List<Map<String, Object>> out = new ArrayList<>();
        working.stream().filter(m -> "system".equals(m.get("role"))).forEach(out::add);
        out.add(Map.of("role", "system", "content", SUMMARY_PREFIX + "\n" + summary));
        for (int i = olderCount; i < turns.size(); i++) out.addAll(turns.get(i));
        if (ContextBuilder.messagesTokens(out) > budget) throw new IllegalStateException("recent context still exceeds input budget after summary");
        return out;
    }
    private static List<List<Map<String, Object>>> turns(List<Map<String, Object>> messages) {
        List<List<Map<String, Object>>> out = new ArrayList<>(); List<Map<String, Object>> turn = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            if ("user".equals(m.get("role")) && !turn.isEmpty()) { out.add(turn); turn = new ArrayList<>(); }
            turn.add(m);
        }
        if (!turn.isEmpty()) out.add(turn); return out;
    }
    private Compactor() {}
}
