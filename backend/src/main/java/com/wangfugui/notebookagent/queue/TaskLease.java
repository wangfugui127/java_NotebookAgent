package com.wangfugui.notebookagent.queue;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative fencing for a handler whose database execution lease has been lost. */
public final class TaskLease {
    private static final ThreadLocal<java.util.function.BooleanSupplier> CURRENT = new ThreadLocal<>();

    static void enter(java.util.function.BooleanSupplier valid) { CURRENT.set(valid); }
    static void leave() { CURRENT.remove(); }

    public static void check() {
        var valid = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || valid != null && !valid.getAsBoolean()) {
            throw new IllegalStateException("task execution lease lost or execution interrupted");
        }
    }

    private TaskLease() {}
}
