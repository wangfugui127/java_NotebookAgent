package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.prompts.Prompts;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.ProviderResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a document retrieval profile (heuristic base + optional LLM enrichment). */
public final class ProfileExtractor {

    private static final Pattern KEYWORDS_LINE = Pattern.compile("(?im)^\\s*(keywords?|key words|关键词|关键字)\\s*[:：]?\\s*(.+)$");
    private static final Pattern ABSTRACT_LINE = Pattern.compile("(?im)^\\s*(abstract|摘要)\\b\\s*[:：]?\\s*(.*)$");

    public static Map<String, Object> build(String title, String fullText, DeepSeekProvider provider,
                                            int maxInputTokens) {
        String abstractText = extractAbstract(fullText);
        List<String> keywords = extractKeywords(fullText);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("one_sentence", "");
        profile.put("data_and_method", "");
        profile.put("results_conclusion", "");
        profile.put("contribution_limitations", "");
        profile.put("keywords", keywords);
        profile.put("region", "");
        profile.put("time_range", "");
        profile.put("authors", new ArrayList<String>());
        profile.put("year", null);
        profile.put("abstract", abstractText);
        profile.put("abstract_source", abstractText == null || abstractText.isBlank() ? "missing" : "original");
        profile.put("extractor_model", "heuristic");

        if (provider == null || fullText == null || fullText.isBlank()) {
            return profile;
        }
        String prompt = Prompts.documentProfilePrompt(title, fullText);
        if (com.wangfugui.notebookagent.agent.ContextBuilder.estimateTokens(prompt) > maxInputTokens) {
            throw new IllegalStateException("document profile exceeds input budget");
        }
        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", Prompts.DOCUMENT_PROFILE_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt));
            ProviderResponse response = provider.invoke(messages, List.of(), null);
            Map<String, Object> parsed = Json.readMap(stripFences(response.content()));
            if (parsed.isEmpty()) {
                return profile;
            }
            profile.put("extractor_model", provider.modelName());
            copy(parsed, profile, "one_sentence");
            copy(parsed, profile, "data_and_method");
            copy(parsed, profile, "results_conclusion");
            copy(parsed, profile, "contribution_limitations");
            copy(parsed, profile, "region");
            copy(parsed, profile, "time_range");
            if (parsed.get("keywords") instanceof List<?> kws) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(keywords);
                for (Object k : kws) {
                    if (k != null && !String.valueOf(k).isBlank()) {
                        merged.add(String.valueOf(k));
                    }
                }
                profile.put("keywords", merged.stream().limit(20).toList());
            }
            if (parsed.get("authors") instanceof List<?> authors) {
                profile.put("authors", authors.stream().filter(a -> a != null && !String.valueOf(a).isBlank())
                        .map(String::valueOf).toList());
            }
            if (parsed.get("year") instanceof Number number) {
                profile.put("year", number.intValue());
            }
            Object generatedAbstract = parsed.get("abstract");
            if (generatedAbstract instanceof String s && !s.isBlank() && (abstractText == null || abstractText.isBlank())) {
                profile.put("abstract", s);
                profile.put("abstract_source", "generated");
            }
        } catch (Exception e) {
            // keep heuristic profile
        }
        return profile;
    }

    public static String profileText(Map<String, Object> profile) {
        List<String> parts = new ArrayList<>();
        append(parts, "标题", null);
        append(parts, "关键词", join(profile.get("keywords")));
        append(parts, "摘要", str(profile.get("abstract")));
        append(parts, "一句话概括", str(profile.get("one_sentence")));
        append(parts, "数据与方法", str(profile.get("data_and_method")));
        append(parts, "结果与结论", str(profile.get("results_conclusion")));
        append(parts, "创新与不足", str(profile.get("contribution_limitations")));
        return String.join("\n", parts);
    }

    private static void append(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + "：" + value);
        }
    }

    private static String join(Object value) {
        if (value instanceof List<?> list) {
            return String.join("、", list.stream().map(String::valueOf).toList());
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        Object value = from.get(key);
        if (value instanceof String s && !s.isBlank()) {
            to.put(key, s);
        }
    }

    static String stripFences(String text) {
        if (text == null) {
            return "{}";
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return t;
    }

    static String extractAbstract(String fullText) {
        if (fullText == null) {
            return null;
        }
        Matcher matcher = ABSTRACT_LINE.matcher(fullText);
        if (!matcher.find()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(matcher.group(2));
        String[] after = fullText.substring(matcher.end()).split("\n");
        for (String line : after) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    break;
                }
                continue;
            }
            if (KEYWORDS_LINE.matcher(trimmed).find() || trimmed.matches("^#{1,6}\\s+.*")) {
                break;
            }
            sb.append(' ').append(trimmed.replaceAll("^#+\\s*", ""));
            if (sb.length() > 3000) {
                break;
            }
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }

    static List<String> extractKeywords(String fullText) {
        if (fullText == null) {
            return List.of();
        }
        Matcher matcher = KEYWORDS_LINE.matcher(fullText);
        if (!matcher.find()) {
            return List.of();
        }
        String value = matcher.group(2);
        int colon = value.indexOf(':');
        int cjkColon = value.indexOf('：');
        int cut = colon < 0 ? cjkColon : cjkColon < 0 ? colon : Math.min(colon, cjkColon);
        if (cut >= 0) {
            value = value.substring(cut + 1);
        }
        List<String> out = new ArrayList<>();
        for (String token : value.split("[;,；，、]")) {
            String trimmed = token.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    private ProfileExtractor() {
    }
}
