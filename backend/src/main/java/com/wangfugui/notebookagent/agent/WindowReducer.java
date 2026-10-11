package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.queue.TaskLease;
import java.util.*;
import java.util.regex.Pattern;

/** Hierarchical reduction of every window, retaining provenance and exact evidence IDs. */
public final class WindowReducer {
    private static final String SYSTEM = "合并所有窗口结论，不得遗漏窗口，不得删除或改写 [evidence:<id>] 引用。压缩重复表述，保留结论与字符/页码回链。";
    private static final Pattern EVIDENCE = Pattern.compile("\\[evidence:[^\\]]+\\]");

    public static List<Map<String,Object>> reduce(String query, List<Map<String,Object>> items,
                                                  int budget, DeepSeekProvider provider) {
        int target = Math.min(Math.max(2000, (int)(budget * .65)), budget - ContextBuilder.messagesTokens(packet(query, List.of())) - 100);
        if (target <= 0) throw new IllegalStateException("window reduction query exceeds input budget");
        List<Map<String,Object>> current = items;
        for (int level = 0; ContextBuilder.estimateTokens(Json.write(current)) > target; level++) {
            if (level >= 16) throw new IllegalStateException("window conclusions cannot be reduced within model budget");
            List<List<Map<String,Object>>> batches = new ArrayList<>();
            List<Map<String,Object>> batch = new ArrayList<>();
            for (var item : current) {
                var trial = new ArrayList<>(batch); trial.add(item);
                if (!batch.isEmpty() && ContextBuilder.estimateTokens(Json.write(trial)) > target) {
                    batches.add(batch); batch = new ArrayList<>();
                }
                batch.add(item);
                if (ContextBuilder.messagesTokens(packet(query, batch)) > budget)
                    throw new IllegalStateException("one window conclusion exceeds reduction input budget");
            }
            if (!batch.isEmpty()) batches.add(batch);
            List<Map<String,Object>> reduced = new ArrayList<>();
            for (var group : batches) {
                TaskLease.check();
                String conclusion = provider.invoke(packet(query, group), List.of(), null).content();
                TaskLease.check();
                var matcher = EVIDENCE.matcher(Json.write(group));
                while (matcher.find()) if (conclusion == null || !conclusion.contains(matcher.group()))
                    throw new IllegalStateException("window reducer omitted an evidence reference");
                List<Object> manifests = new ArrayList<>();
                for (var item : group) manifests.add(item.getOrDefault("manifest", item.getOrDefault("manifests", Map.of())));
                reduced.add(Map.of("manifests", manifests, "conclusion", conclusion == null ? "" : conclusion));
            }
            if (ContextBuilder.estimateTokens(Json.write(reduced)) >= ContextBuilder.estimateTokens(Json.write(current)))
                throw new IllegalStateException("window conclusions cannot be reduced within model budget");
            current = reduced;
        }
        return current;
    }

    private static List<Map<String,Object>> packet(String query, List<Map<String,Object>> items) {
        return List.of(Map.of("role", "system", "content", SYSTEM),
                Map.of("role", "user", "content", "原始任务：" + query + "\n窗口结论：" + Json.write(items)));
    }
    private WindowReducer() {}
}
