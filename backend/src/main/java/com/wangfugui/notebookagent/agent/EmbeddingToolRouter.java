package com.wangfugui.notebookagent.agent;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Python-compatible embedding ranking, with deterministic offline routing. */
public final class EmbeddingToolRouter {
    private final Function<List<String>,List<float[]>> embed;
    private List<String> cacheKey = List.of();
    private List<float[]> vectors = List.of();
    private static final Map<String,List<String>> HINTS = Map.of(
            "task_update", List.of("计划", "步骤", "进度", "task"),
            "add_paper_to_notebook", List.of("加入工作区", "加入notebook", "导入", "保存到", "采集"),
            "remove_notebook_source", List.of("删除来源", "移除来源", "删除论文"));

    public EmbeddingToolRouter(Function<List<String>,List<float[]>> embed) { this.embed = embed; }

    public synchronized List<String> select(String query, List<ToolDefinition> catalog, int limit) {
        if (catalog.isEmpty() || limit <= 0) return List.of();
        Map<String,Double> scores = new HashMap<>();
        boolean offline = false;
        try {
            List<String> key = catalog.stream().map(t -> t.name() + ": " + t.description()).toList();
            if (!key.equals(cacheKey)) {
                var fresh = embed.apply(key);
                if (fresh == null || fresh.size() != catalog.size()) throw new IllegalStateException("invalid tool embeddings");
                vectors = fresh; cacheKey = key;
            }
            float[] q = embed.apply(List.of(query)).getFirst();
            for (int i=0; i<catalog.size(); i++) scores.put(catalog.get(i).name(), cosine(q,vectors.get(i)));
        } catch (RuntimeException failure) {
            offline = true;
            var q = tokens(query);
            for (var tool : catalog) {
                double score = cosine(q,tokens(tool.name()+" "+tool.description()));
                for (String hint : HINTS.getOrDefault(tool.name(),List.of()))
                    if (query.toLowerCase(Locale.ROOT).contains(hint)) score += .35;
                if (tool.name().equals("task_update")) score += .05;
                scores.put(tool.name(),score);
            }
        }
        final boolean useFallback = offline;
        var chosen = catalog.stream().map(ToolDefinition::name)
                .filter(n -> !useFallback || scores.get(n) > 0)
                .sorted(Comparator.<String>comparingDouble(n -> -scores.get(n)).thenComparing(n -> n))
                .limit(limit).toList();
        return chosen.isEmpty() ? List.of(catalog.getFirst().name()) : chosen;
    }
    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalStateException("embedding dimensions differ");
        double dot=0, x=0, y=0;
        for (int i=0;i<a.length;i++) { dot+=a[i]*b[i];x+=a[i]*a[i];y+=b[i]*b[i]; }
        return x>0 && y>0 ? dot/Math.sqrt(x*y) : 0;
    }
    private static Map<String,Integer> tokens(String text) {
        Map<String,Integer> out = new HashMap<>();
        var matcher=Pattern.compile("[\\p{L}\\p{N}_]+").matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find()) {
            String word=matcher.group();out.merge(word,1,Integer::sum);
            if(word.matches(".*[\\u3400-\\u9fff].*")) for(int i=0;i+1<word.length();i++) out.merge(word.substring(i,i+2),1,Integer::sum);
        }
        return out;
    }
    private static double cosine(Map<String,Integer> a, Map<String,Integer> b) {
        double dot=0,x=0,y=0;
        for(var e:a.entrySet()) { dot+=e.getValue()*b.getOrDefault(e.getKey(),0);x+=e.getValue()*e.getValue(); }
        for(int v:b.values()) y+=v*v;
        return x>0 && y>0 ? dot/Math.sqrt(x*y) : 0;
    }
}
