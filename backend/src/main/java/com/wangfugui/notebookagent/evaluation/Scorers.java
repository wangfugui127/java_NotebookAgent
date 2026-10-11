package com.wangfugui.notebookagent.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-case scoring identical to the Python scorers. */
public final class Scorers {

    public static final Map<String, Double> DEFAULT_SCORE_CONFIG = Map.of(
            "route_weight", 0.30,
            "retrieval_weight", 0.30,
            "citation_weight", 0.20,
            "keyword_weight", 0.20,
            "pass_threshold", 60.0);

    public static Map<String, Object> scoreCase(List<String> expectedTools, List<String> actualTools,
                                                 List<String> expectedSourceTitles, List<String> evidenceTitles,
                                                 List<String> citationTitles, List<String> missingSources,
                                                 List<String> requiredKeywords, String answer, String runStatus,
                                                 Map<String, Double> config) {
        Map<String, Double> merged = new LinkedHashMap<>(DEFAULT_SCORE_CONFIG);
        if (config != null) merged.putAll(config);
        config = merged;
        expectedTools = normalize(expectedTools);
        expectedSourceTitles = normalize(expectedSourceTitles);
        requiredKeywords = normalize(requiredKeywords);
        missingSources = normalize(missingSources);
        Map<String, Object> detail = new LinkedHashMap<>();
        boolean hasRoute = expectedTools != null && !expectedTools.isEmpty();
        boolean hasSources = expectedSourceTitles != null && !expectedSourceTitles.isEmpty();
        boolean hasKeywords = requiredKeywords != null && !requiredKeywords.isEmpty();

        Double routeScore = hasRoute ? ratio(intersect(normSet(expectedTools), normSet(actualTools)).size(),
                normSet(expectedTools).size()) : 1.0;

        boolean sourcesMissing = missingSources != null && !missingSources.isEmpty();
        List<String> matchedSources = matchTitles(expectedSourceTitles, evidenceTitles);
        List<String> matchedCitations = matchTitles(expectedSourceTitles, citationTitles);
        Double retrievalScore = hasSources ? (sourcesMissing ? 0.0 : matchedSources.size() / (double) expectedSourceTitles.size()) : 1.0;
        Double citationScore = hasSources ? (sourcesMissing ? 0.0 : matchedCitations.size() / (double) expectedSourceTitles.size()) : 1.0;

        List<String> matchedKeywords = new ArrayList<>();
        List<String> missingKeywords = new ArrayList<>();
        if (hasKeywords) {
            String lowerAnswer = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
            for (String keyword : requiredKeywords) {
                if (lowerAnswer.contains(keyword.toLowerCase(Locale.ROOT))) {
                    matchedKeywords.add(keyword);
                } else {
                    missingKeywords.add(keyword);
                }
            }
        }
        Double keywordScore = hasKeywords ? matchedKeywords.size() / (double) requiredKeywords.size() : 1.0;

        List<Double> weights = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        if (hasRoute) {
            weights.add(config.get("route_weight"));
            scores.add(routeScore);
        }
        if (hasSources) {
            weights.add(config.get("retrieval_weight"));
            scores.add(retrievalScore);
            weights.add(config.get("citation_weight"));
            scores.add(citationScore);
        }
        if (hasKeywords) {
            weights.add(config.get("keyword_weight"));
            scores.add(keywordScore);
        }
        double total;
        if (weights.isEmpty()) {
            total = 0.0;
        } else {
            double weighted = 0.0;
            double weightSum = 0.0;
            for (int i = 0; i < weights.size(); i++) {
                weighted += scores.get(i) * weights.get(i);
                weightSum += weights.get(i);
            }
            total = Math.round((weighted / weightSum) * 1000.0) / 10.0;
        }
        boolean completed = "completed".equals(runStatus);
        boolean passed = completed && !weights.isEmpty() && total >= config.get("pass_threshold");
        String status = !completed || weights.isEmpty() ? "error" : (passed ? "passed" : "failed");

        detail.put("matched_sources", matchedSources);
        detail.put("matched_citations", matchedCitations);
        detail.put("matched_keywords", matchedKeywords);
        detail.put("missing_keywords", missingKeywords);
        detail.put("missing_sources", missingSources == null ? List.of() : missingSources);
        detail.put("run_status", runStatus);
        if (weights.isEmpty()) {
            detail.put("score_note", "未配置任何评分维度，无法打分");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route_score", hasRoute ? routeScore : null);
        result.put("retrieval_score", hasSources ? retrievalScore : null);
        result.put("citation_score", hasSources ? citationScore : null);
        result.put("keyword_score", hasKeywords ? keywordScore : null);
        result.put("total_score", weights.isEmpty() ? null : total);
        result.put("passed", passed);
        result.put("status", status);
        result.put("detail", detail);
        return result;
    }

    static List<String> matchTitles(List<String> needles, List<String> haystack) {
        List<String> matched = new ArrayList<>();
        if (needles == null) {
            return matched;
        }
        List<String> titles = normalize(haystack);
        for (String needle : normalize(needles)) {
            String n = needle.toLowerCase(Locale.ROOT);
            for (String title : titles) {
                String t = title.toLowerCase(Locale.ROOT);
                if (t.contains(n) || n.contains(t)) {
                    matched.add(needle);
                    break;
                }
            }
        }
        return matched;
    }

    private static List<String> normalize(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
    }

    private static java.util.Set<String> normSet(List<String> values) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    set.add(value.strip());
                }
            }
        }
        return set;
    }

    private static java.util.Set<String> intersect(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    private static double ratio(int matched, int total) {
        return total == 0 ? 1.0 : matched / (double) total;
    }

    private Scorers() {
    }
}
