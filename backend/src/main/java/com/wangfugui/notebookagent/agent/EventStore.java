package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.RunEvent;
import com.wangfugui.notebookagent.repository.RunEventRepository;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Durable run event log plus best-effort Redis pub/sub fan-out for live SSE. */
@Component
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    private final RunEventRepository events;
    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final AgentRunRepository runs;

    public EventStore(RunEventRepository events, StringRedisTemplate redis, AppProperties props, AgentRunRepository runs) {
        this.events = events;
        this.redis = redis;
        this.props = props;
        this.runs = runs;
    }

    public String channel(String runId) {
        return props.getQueue().getKeyPrefix() + ":agent-run:" + runId;
    }

    /** Persist a durable event and publish it. */
    @Transactional
    public RunEvent emit(String runId, String eventType, Map<String, Object> payload) {
        runs.lockById(runId).orElseThrow(() -> new IllegalStateException("run not found"));
        int sequence = (int) events.countByRunId(runId) + 1;
        RunEvent event = new RunEvent();
        event.setRunId(runId);
        event.setSequence(sequence);
        event.setEventType(eventType);
        event.setPayload(Json.write(payload == null ? Map.of() : payload));
        events.saveAndFlush(event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(runId, eventType, payload, sequence); }
            });
        } else publish(runId, eventType, payload, sequence);
        return event;
    }

    /** Publish a transient (non-persisted) event such as text_delta. */
    public void publishTransient(String runId, String eventType, Map<String, Object> payload) {
        publish(runId, eventType, payload, null);
    }

    private void publish(String runId, String eventType, Map<String, Object> payload, Integer sequence) {
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("sequence", sequence);
            envelope.put("type", eventType);
            envelope.put("payload", payload == null ? Map.of() : payload);
            redis.convertAndSend(channel(runId), Json.write(envelope));
        } catch (Exception e) {
            log.debug("event publish failed (durable log retained): {}", e.getMessage());
        }
    }

    public List<RunEvent> replay(String runId, int after) {
        return events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId, after);
    }
}
