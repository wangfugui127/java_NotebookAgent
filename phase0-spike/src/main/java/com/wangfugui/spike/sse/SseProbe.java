package com.wangfugui.spike.sse;

import com.wangfugui.spike.SpikeApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boots the Spring app, calls the SSE endpoint, and validates the wire format the
 * frontend parser depends on: text_delta blocks must have no id, durable blocks must
 * carry strictly increasing ids, and a terminal event must be present.
 */
public final class SseProbe {

    private static final Pattern ID = Pattern.compile("(?m)^id:\\s*(\\d+)\\s*$");
    private static final Pattern EVENT = Pattern.compile("(?m)^event:\\s*(.+?)\\s*$");

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SpikeApplication.class)
                .properties("server.port=18080", "spring.main.banner-mode=off")
                .run()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:18080/api/v1/spike/events"))
                    .header("Authorization", "Bearer spike")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[sse] status=" + resp.statusCode()
                    + " content-type=" + resp.headers().firstValue("content-type").orElse(""));

            List<String> blocks = new ArrayList<>();
            for (String b : resp.body().replace("\r\n", "\n").split("\n\n")) {
                if (!b.isBlank()) {
                    blocks.add(b);
                }
            }
            int textDeltaNoId = 0;
            int textDeltaWithId = 0;
            List<Long> durableIds = new ArrayList<>();
            boolean terminal = false;
            for (String block : blocks) {
                if (block.startsWith(":")) {
                    continue; // comment/ping
                }
                Matcher em = EVENT.matcher(block);
                String event = em.find() ? em.group(1) : "message";
                Matcher im = ID.matcher(block);
                boolean hasId = im.find();
                if ("text_delta".equals(event)) {
                    if (hasId) {
                        textDeltaWithId++;
                    } else {
                        textDeltaNoId++;
                    }
                } else if (hasId) {
                    durableIds.add(Long.parseLong(im.group(1)));
                }
                if (List.of("run_completed", "run_failed", "run_cancelled").contains(event)) {
                    terminal = true;
                }
                System.out.println("[sse] block event=" + event + " id=" + (hasId ? im.group(1) : "<none>"));
            }

            boolean increasing = true;
            for (int i = 1; i < durableIds.size(); i++) {
                if (durableIds.get(i) <= durableIds.get(i - 1)) {
                    increasing = false;
                }
            }
            System.out.println("[sse] text_delta no-id=" + textDeltaNoId + " with-id=" + textDeltaWithId
                    + " durable ids=" + durableIds + " increasing=" + increasing + " terminal=" + terminal);
            if (textDeltaNoId != 2 || textDeltaWithId != 0 || !increasing || !terminal) {
                throw new IllegalStateException("SSE wire format did not match the frontend contract");
            }
            System.out.println("[sse] ALL CAPABILITIES OK (text_delta without id; durable ids increasing; terminal)");
        }
    }

    private SseProbe() {
    }
}
