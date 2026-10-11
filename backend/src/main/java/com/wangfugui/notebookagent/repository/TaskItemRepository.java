package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface TaskItemRepository extends JpaRepository<TaskItem, String> {
    List<TaskItem> findByRunIdOrderByOrdinal(String runId);

    void deleteByRunId(String runId);
}
