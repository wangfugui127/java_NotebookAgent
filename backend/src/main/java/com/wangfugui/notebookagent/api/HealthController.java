package com.wangfugui.notebookagent.api;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.queue.TaskWorker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import javax.sql.DataSource;
import java.util.Map;
@RestController
public class HealthController {
    private final DataSource db; private final StringRedisTemplate redis; private final TaskWorker worker; private final AppProperties props;
    public HealthController(DataSource db, StringRedisTemplate redis, TaskWorker worker, AppProperties props) {
        this.db=db; this.redis=redis; this.worker=worker; this.props=props;
    }
    @GetMapping("/health") public ResponseEntity<Map<String,String>> health() {
        try (var connection=db.getConnection(); var statement=connection.createStatement()) {
            statement.setQueryTimeout(5); statement.execute("SELECT 1");
            if (!"PONG".equals(redis.execute((RedisCallback<String>) c -> c.ping()))) throw new IllegalStateException();
            if (props.getQueue().isWorkerEnabled() && !worker.isRunning()) throw new IllegalStateException();
            return ResponseEntity.ok(Map.of("status","ok"));
        } catch (Exception e) { return ResponseEntity.status(503).body(Map.of("status","unavailable")); }
    }
}
