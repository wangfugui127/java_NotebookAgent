package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.RunEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface RunEventRepository extends JpaRepository<RunEvent, Long> {
    List<RunEvent> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, int sequence);

    long countByRunId(String runId);
}
