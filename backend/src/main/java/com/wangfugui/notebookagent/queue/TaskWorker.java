package com.wangfugui.notebookagent.queue;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.TaskExecution;
import com.wangfugui.notebookagent.repository.TaskExecutionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis delivery is at least once; atomic, fenced DB leases decide who may execute. */
@Component
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final AppProperties props;
    private final StringRedisTemplate redis;
    private final TaskExecutionRepository tasks;
    private final Map<String, TaskHandler> handlers = new HashMap<>();
    private final String consumer = "worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread loop;
    private ScheduledThreadPoolExecutor heartbeat;
    private String reclaimCursor = "0-0";

    public TaskWorker(AppProperties props, StringRedisTemplate redis, TaskExecutionRepository tasks,
                      List<TaskHandler> handlerList) {
        this.props = props;
        this.redis = redis;
        this.tasks = tasks;
        handlerList.forEach(h -> handlers.put(h.type(), h));
    }
    private String streamKey() { return props.getQueue().getKeyPrefix() + ":" + props.getQueue().getStream(); }
    private String group() { return props.getQueue().getConsumerGroup(); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String string(Object value) {
        return value instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    private void ensureGroup() {
        try {
            redis.execute((RedisCallback<Object>) c -> c.execute("XGROUP", bytes("CREATE"), bytes(streamKey()),
                    bytes(group()), bytes("0"), bytes("MKSTREAM")));
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP") &&
                    !String.valueOf(e.getCause()).contains("BUSYGROUP")) throw e;
        }
    }
    @PostConstruct
    public void start() {
        if (!props.getQueue().isWorkerEnabled() || handlers.isEmpty() || loop != null && loop.isAlive()) return;
        running.set(true);
        heartbeat = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "task-heartbeat"); t.setDaemon(true); return t;
        });
        heartbeat.setRemoveOnCancelPolicy(true);
        loop = new Thread(this::runLoop, "task-worker");
        loop.setDaemon(true);
        loop.start();
    }
    @PreDestroy
    public void stop() {
        running.set(false);
        if (loop != null) loop.interrupt();
        if (heartbeat != null) heartbeat.shutdownNow();
    }
    public boolean isRunning() { return running.get() && loop != null && loop.isAlive(); }
    private void runLoop() {
        while (running.get()) {
            try {
                ensureGroup();
                reclaimStale();
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(group(), consumer),
                        StreamReadOptions.empty().count(1).block(Duration.ofMillis(props.getQueue().getPollBlockMs())),
                        StreamOffset.create(streamKey(), ReadOffset.lastConsumed()));
                if (records != null) for (var record : records) process(record.getValue(), record.getId().getValue());
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("worker loop error: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
            }
        }
    }
    @SuppressWarnings("unchecked")
    private void reclaimStale() {
        var claimed = redis.execute((RedisCallback<io.lettuce.core.models.stream.ClaimedMessages<byte[],byte[]>>) c -> {
            var commands = (io.lettuce.core.api.async.RedisStreamAsyncCommands<byte[],byte[]>) c.getNativeConnection();
            try {
                return commands.xautoclaim(bytes(streamKey()), new io.lettuce.core.XAutoClaimArgs<byte[]>()
                        .consumer(io.lettuce.core.Consumer.from(bytes(group()), bytes(consumer)))
                        .minIdleTime(Duration.ofSeconds(props.getQueue().getVisibilityTimeoutSeconds()))
                        .startId(reclaimCursor).count(50)).get(10, TimeUnit.SECONDS);
            } catch (Exception e) { throw new IllegalStateException("XAUTOCLAIM failed", e); }
        });
        if (claimed == null) return;
        reclaimCursor = claimed.getId();
        for (var message : claimed.getMessages()) {
            Map<Object,Object> payload = new LinkedHashMap<>();
            message.getBody().forEach((k,v) -> payload.put(string(k),string(v)));
            process(payload,message.getId());
        }
    }
    // Package visibility permits regression tests to exercise actual delivery decisions.
    void process(Map<Object, Object> value, String recordId) {
        String taskId = value.get("task_id") == null ? null : value.get("task_id").toString();
        TaskExecution task = taskId == null ? null : tasks.findById(taskId).orElse(null);
        if (task == null || Set.of(TaskExecution.SUCCEEDED, TaskExecution.DEAD).contains(task.getStatus())) {
            ack(recordId); return;
        }
        TaskHandler handler = handlers.get(task.getTaskType());
        Map<String, Object> payload = Json.readMap(task.getPayload());
        if (task.getAttempt() >= task.getMaxAttempts() && !TaskExecution.RUNNING.equals(task.getStatus())) {
            String error = "maximum task attempts exhausted after worker interruption";
            if (tasks.markExhausted(taskId, error, Instant.now()) == 1 && handler != null) handler.onFailure(payload, error, false);
            ack(recordId); return;
        }
        Instant now = Instant.now();
        // A new token per execution also fences an older execution on the same process.
        String owner = consumer + ":" + UUID.randomUUID();
        if (tasks.claim(taskId, owner, now.plusSeconds(props.getQueue().getLeaseSeconds()), now) == 0) {
            // Keep live/early-retry deliveries pending. A terminal row alone permits ACK.
            TaskExecution current = tasks.findById(taskId).orElse(null);
            if (current == null || Set.of(TaskExecution.SUCCEEDED, TaskExecution.DEAD).contains(current.getStatus())) ack(recordId);
            return;
        }
        TaskExecution fresh = tasks.findById(taskId).orElseThrow();
        AtomicBoolean valid = new AtomicBoolean(true);
        Thread executing = Thread.currentThread();
        long period = Math.max(1, props.getQueue().getLeaseSeconds() / 3);
        ScheduledFuture<?> beat = heartbeat == null ? null : heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (tasks.renewLease(taskId, owner, Instant.now().plusSeconds(props.getQueue().getLeaseSeconds()), Instant.now()) != 1) {
                    valid.set(false); executing.interrupt();
                }
            } catch (Exception e) {
                valid.set(false); executing.interrupt();
            }
        }, period, period, TimeUnit.SECONDS);
        TaskLease.enter(() -> valid.get() && tasks.findById(taskId).map(t ->
                TaskExecution.RUNNING.equals(t.getStatus()) && owner.equals(t.getLeaseOwner())
                        && t.getLeaseUntil() != null && !t.getLeaseUntil().isBefore(Instant.now())).orElse(false));
        try {
            if (handler == null) throw new IllegalStateException("no handler for task type " + fresh.getTaskType());
            handler.handle(taskId, payload);
            TaskLease.check();
            if (tasks.markSucceeded(taskId, owner, Instant.now()) == 1) ack(recordId);
        } catch (Exception e) {
            if (valid.get() && !Thread.currentThread().isInterrupted()) {
                try { TaskLease.check(); } catch (Exception lost) { return; }
                boolean retrying = fresh.getAttempt() < fresh.getMaxAttempts();
                if (handler != null) handler.onFailure(payload, String.valueOf(e.getMessage()), retrying);
                int updated = retrying
                        ? tasks.markRetrying(taskId, Instant.now().plusSeconds(Math.min(300, 1L << Math.min(8, fresh.getAttempt()))),
                            String.valueOf(e.getMessage()), owner, Instant.now())
                        : tasks.markDead(taskId, String.valueOf(e.getMessage()), owner, Instant.now());
                if (updated == 1) ack(recordId);
                log.warn("task {} attempt {} failed, retrying={}: {}", taskId, fresh.getAttempt(), retrying, e.getMessage());
            }
        } finally {
            valid.set(false);
            if (beat != null) beat.cancel(false);
            TaskLease.leave();
            if (running.get()) Thread.interrupted();
        }
    }
    private void ack(String id) { redis.opsForStream().acknowledge(streamKey(), group(), id); }
}
