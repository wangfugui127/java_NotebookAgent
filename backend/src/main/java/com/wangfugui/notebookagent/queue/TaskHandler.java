package com.wangfugui.notebookagent.queue;

import java.util.Map;

/** A task type handler executed by the worker after a successful DB claim. */
public interface TaskHandler {
    default void onFailure(Map<String, Object> payload, String error, boolean retrying) {}

    String type();

    void handle(String taskId, Map<String, Object> payload) throws Exception;
}
