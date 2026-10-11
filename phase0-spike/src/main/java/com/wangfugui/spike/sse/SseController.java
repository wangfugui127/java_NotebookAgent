package com.wangfugui.spike.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 0 SSE probe endpoint. Emits the real event ordering the frontend expects:
 * durable events carry an {@code id:} (strictly increasing sequence) while
 * {@code text_delta} carries NO id and is not persisted.
 */
@RestController
public class SseController {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @GetMapping(value = "/api/v1/spike/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().id("1").name("run_started").data("{}"));
                emitter.send(SseEmitter.event().name("text_delta").data("{\"delta\":\"你\"}"));
                Thread.sleep(30);
                emitter.send(SseEmitter.event().name("text_delta").data("{\"delta\":\"好\"}"));
                emitter.send(SseEmitter.event().id("2").name("tool_call")
                        .data("{\"name\":\"search_notebook\",\"risk\":\"read\"}"));
                emitter.send(SseEmitter.event().id("3").name("run_completed")
                        .data("{\"answer\":\"你好\",\"citations\":[]}"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
