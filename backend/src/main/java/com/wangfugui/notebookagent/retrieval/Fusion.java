package com.wangfugui.notebookagent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Reciprocal Rank Fusion (k defaults to 60) across result sets. */
public final class Fusion {

    public static List<SearchHit> reciprocalRankFusion(Map<String, List<SearchHit>> resultSets, int k, int limit) {
        Map<String, SearchHit> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, TreeSet<String>> sources = new LinkedHashMap<>();
        for (Map.Entry<String, List<SearchHit>> entry : resultSets.entrySet()) {
            List<SearchHit> hits = entry.getValue();
            for (int rank = 0; rank < hits.size(); rank++) {
                SearchHit hit = hits.get(rank);
                if (hit.getChunkId() == null) {
                    continue;
                }
                byId.putIfAbsent(hit.getChunkId(), hit);
                scores.merge(hit.getChunkId(), 1.0 / (k + rank + 1), Double::sum);
                sources.computeIfAbsent(hit.getChunkId(), x -> new TreeSet<>()).add(entry.getKey());
            }
        }
        List<SearchHit> ordered = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed())
                .limit(limit)
                .forEach(e -> {
                    SearchHit hit = byId.get(e.getKey());
                    hit.setScore(e.getValue());
                    hit.setSources(new ArrayList<>(sources.getOrDefault(e.getKey(), new TreeSet<>())));
                    ordered.add(hit);
                });
        return ordered;
    }

    private Fusion() {
    }
}
