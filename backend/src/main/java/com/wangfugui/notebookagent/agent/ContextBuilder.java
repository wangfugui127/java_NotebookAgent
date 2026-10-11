package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.prompts.Prompts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds provider message packets with token budgeting and attachment windowing. */
public final class ContextBuilder {

    public record Attachment(Map<String, Object> payload) {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3400 && c <= 0x9fff) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + (int) Math.ceil(other / 3.2));
    }

    public static int messagesTokens(List<Map<String, Object>> messages) {
        return estimateTokens(Json.write(messages));
    }

    public static List<Map<String, Object>> baseMessages(List<Map<String, Object>> history, String summary,
                                                         AgentState state, String notebookIndex, Object evidence,
                                                         Object skills, Object tools, String query) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", Prompts.ROOT_AGENT_SYSTEM_PROMPT));
        messages.add(Map.of("role", "system", "content",
                Prompts.runtimeContextPrompt(summary, state.toMap(), notebookIndex, evidence, skills, tools)));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", query));
        return messages;
    }

    /** One packet (single mode) or many packets (windowed mode). */
    public static List<List<Map<String, Object>>> withAttachments(List<Map<String, Object>> base, String query,
                                                                  List<Map<String, Object>> attachments, int budget) {
        return withAttachments(base, query, attachments, budget, new ArrayList<>());
    }

    public static List<List<Map<String,Object>>> withAttachments(List<Map<String,Object>> base, String query,
            List<Map<String,Object>> attachments, int budget, List<Map<String,Object>> windowManifests) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of(base);
        }
        StringBuilder queryWithAttachments = new StringBuilder(query);
        for (Map<String, Object> attachment : attachments) {
            queryWithAttachments.append(attachmentBlock(attachment));
        }
        List<Map<String, Object>> single = new ArrayList<>(base.subList(0, base.size() - 1));
        single.add(Map.of("role", "user", "content", queryWithAttachments.toString()));
        if (messagesTokens(single) <= budget) {
            return List.of(single);
        }
        // Find the largest complete text window that fits the actual packet budget.
        List<List<Map<String,Object>>> packets = new ArrayList<>();
        for (Map<String,Object> attachment : attachments) {
            String text = String.valueOf(attachment.get("text"));
            int start = 0;
            do {
                int low = start, high = text.length();
                List<Map<String,Object>> best = null;
                int bestEnd = start;
                while (low <= high) {
                    int end = low + (high - low) / 2;
                    Map<String,Object> window = new LinkedHashMap<>(attachment);
                    window.put("text", text.substring(start, end));
                    window.put("evidence", evidenceForWindow(attachment, start, end));
                    String prompt = query + "\n这是全文处理的一个窗口，请处理全部正文并保留证据标记。字符范围 ["
                            + start + ", " + end + ")，完整附件长度 " + text.length() + "。" + attachmentBlock(window);
                    List<Map<String,Object>> packet = new ArrayList<>(base.subList(0, base.size()-1));
                    packet.add(Map.of("role","user","content",prompt));
                    if (messagesTokens(packet) <= budget) { best=packet; bestEnd=end; low=end+1; }
                    else high=end-1;
                }
                if (best == null || bestEnd <= start && !text.isEmpty())
                    throw new IllegalStateException("attachment metadata and base context exceed input budget");
                Map<String,Object> manifest = new LinkedHashMap<>();
                manifest.put("document_id", attachment.get("document_id"));
                manifest.put("document_version_id", attachment.get("document_version_id"));
                manifest.put("name", attachment.get("name"));
                manifest.put("char_start", start); manifest.put("char_end", bestEnd);
                manifest.put("evidence", evidenceForWindow(attachment, start, bestEnd));
                windowManifests.add(manifest);
                packets.add(best); start=bestEnd;
            } while (start < text.length());
        }
        return packets;
    }

    private static List<Map<String,Object>> evidenceForWindow(Map<String,Object> attachment, int start, int end) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (attachment.get("evidence") instanceof List<?> rows) for (Object raw : rows) {
            Map<String,Object> row = Json.readMap(Json.write(raw));
            int from = row.get("char_start") instanceof Number n ? n.intValue() : 0;
            int to = row.get("char_end") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
            if (from < end && to > start) { row.remove("text"); out.add(row); }
        }
        return out;
    }

    public static String attachmentBlock(Map<String, Object> attachment) {
        return "\n\n--- 附件开始 ---\n名称: " + attachment.get("name")
                + "\ndocument_id: " + attachment.get("document_id")
                + "\nversion_id: " + attachment.get("document_version_id")
                + "\n用户附件指令: " + (attachment.get("instruction") == null || String.valueOf(attachment.get("instruction")).isBlank()
                ? "按当前问题处理全文" : attachment.get("instruction"))
                + "\n可引用证据:\n" + Json.write(evidenceForWindow(attachment, 0, Integer.MAX_VALUE))
                + "\n完整正文:\n" + attachment.get("text")
                + "\n--- 附件结束 ---";
    }

    public static Map<String, Object> manifest(int modelWindow, int effectiveBudget, int estimatedTokens,
                                               Map<String, Integer> layers, List<Map<String, Object>> attachments,
                                               List<Map<String, Object>> omissions, String mode) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("model_context_window", modelWindow);
        manifest.put("effective_input_budget", effectiveBudget);
        manifest.put("estimated_tokens", estimatedTokens);
        manifest.put("layers", layers);
        manifest.put("attachments", attachments);
        manifest.put("omissions", omissions);
        manifest.put("mode", mode);
        manifest.put("windows", "windowed".equals(mode) ? attachments.size() : 1);
        return manifest;
    }

    private ContextBuilder() {
    }
}
