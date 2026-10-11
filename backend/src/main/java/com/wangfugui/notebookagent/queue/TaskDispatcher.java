package com.wangfugui.notebookagent.queue;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.domain.TaskExecution;
import com.wangfugui.notebookagent.repository.TaskExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes NEW and due RETRYING tasks to the Redis stream (transactional outbox dispatcher). */
@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskExecutionRepository tasks;
    private final StringRedisTemplate redis;
    private final AppProperties props;

    public TaskDispatcher(TaskExecutionRepository tasks, StringRedisTemplate redis, AppProperties props) {
        this.tasks = tasks;
        this.redis = redis;
        this.props = props;
    }

    public String streamKey() {
        return props.getQueue().getKeyPrefix() + ":" + props.getQueue().getStream();
    }

    @Scheduled(fixedDelayString = "${app.queue.dispatcher-interval-ms:1000}")
    @Transactional
    public void dispatch() {
        Instant now = Instant.now();
        for (TaskExecution task : tasks.findTop50ByStatusAndLeaseUntilLessThanOrderByLeaseUntilAsc(TaskExecution.RUNNING, now)) {
            tasks.recoverExpired(task.getId(), now);
        }
        Instant cutoff = now.minusSeconds(Math.max(30, props.getQueue().getVisibilityTimeoutSeconds()));
        for (TaskExecution task : tasks.findTop50ByStatusAndUpdatedAtLessThanOrderByUpdatedAtAsc(TaskExecution.DISPATCHED, cutoff)) {
            tasks.recoverDispatched(task.getId(), cutoff, now);
        }
        publishNew(tasks.findTop50ByStatusOrderByCreatedAtAsc(TaskExecution.NEW));
        publishNew(tasks.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                TaskExecution.RETRYING, Instant.now()));
    }

    private void publishNew(List<TaskExecution> candidates) {
        for (TaskExecution task : candidates) {
            try {
                Map<String, String> record = new LinkedHashMap<>();
                record.put("task_id", task.getId());
                record.put("task_type", task.getTaskType());
                record.put("attempt", String.valueOf(task.getAttempt()));
                MapRecord<String, String, String> added = MapRecord.create(streamKey(), record);
                var recordId = redis.opsForStream().add(added);
                if (recordId == null) {
                    throw new IllegalStateException("Redis did not return a stream id");
                }
                String streamId = recordId.getValue();
                tasks.markDispatched(task.getId(), task.getStatus(), streamId, Instant.now());
            } catch (Exception e) {
                log.warn("task dispatch failed for {}: {}", task.getId(), e.getMessage());
            }
        }
    }
}
