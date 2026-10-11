package com.wangfugui.notebookagent.agent;

import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A subscription belongs to one HTTP connection; parallel tabs cannot unsubscribe each other. */
@Component
public class SseHub {
    public static final class Subscription implements AutoCloseable {
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Runnable cleanup = () -> {};
        public String poll(long timeoutMs) throws InterruptedException { return queue.poll(timeoutMs, TimeUnit.MILLISECONDS); }
        public boolean isClosed() { return closed.get(); }
        @Override public void close() { if (closed.compareAndSet(false, true)) cleanup.run(); }
    }
    private final RedisMessageListenerContainer container;
    private final EventStore events;
    public SseHub(RedisMessageListenerContainer container, EventStore events) {
        this.container = container; this.events = events;
    }
    public Subscription subscribe(String runId) {
        Subscription subscription = new Subscription();
        MessageListener listener = (message, pattern) -> {
            if (!subscription.closed.get()) subscription.queue.offer(new String(message.getBody(), StandardCharsets.UTF_8));
        };
        try {
            container.addMessageListener(listener, new ChannelTopic(events.channel(runId)));
            subscription.cleanup = () -> container.removeMessageListener(listener);
        } catch (Exception ignored) {
            // The controller can continue replaying the durable DB event log while Redis is unavailable.
        }
        return subscription;
    }
}
