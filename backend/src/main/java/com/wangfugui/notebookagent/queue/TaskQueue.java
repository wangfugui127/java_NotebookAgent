package com.wangfugui.notebookagent.queue;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.TaskExecution;
import com.wangfugui.notebookagent.repository.TaskExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Durable task intake. The TaskExecution row is written in the caller's transaction
 * (transactional outbox); a scheduler publishes NEW rows to Redis exactly once.
 */
@Service
public class TaskQueue {

    private final TaskExecutionRepository tasks;
    private final EntityManager entityManager;

    public TaskQueue(TaskExecutionRepository tasks, EntityManager entityManager) {
        this.tasks = tasks;
        this.entityManager = entityManager;
    }

    /** Enqueue within the current transaction. maxAttempts = 1 + maxRetries (Celery semantics). */
    @Transactional
    public TaskExecution enqueue(String taskType, Map<String, Object> payload, String dedupKey, int maxAttempts) {
        if (dedupKey != null) {
            entityManager.flush();
            entityManager.createNativeQuery("INSERT INTO task_execution "
                    + "(id,task_type,payload,dedup_key,max_attempts) VALUES (:id,:type,:payload,:key,:max) "
                    + "ON DUPLICATE KEY UPDATE dedup_key=dedup_key")
                    .setParameter("id", UUID.randomUUID().toString()).setParameter("type", taskType)
                    .setParameter("payload", Json.write(payload == null ? Map.of() : payload))
                    .setParameter("key", dedupKey).setParameter("max", Math.max(1, maxAttempts)).executeUpdate();
            return tasks.findByDedupKey(dedupKey).orElseThrow();
        }
        TaskExecution task = new TaskExecution();
        task.setTaskType(taskType);
        task.setPayload(Json.write(payload == null ? new LinkedHashMap<>() : payload));
        task.setDedupKey(dedupKey);
        task.setMaxAttempts(Math.max(1, maxAttempts));
        return tasks.save(task);
    }
}
