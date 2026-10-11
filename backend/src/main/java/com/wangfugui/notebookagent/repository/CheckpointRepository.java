package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Checkpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface CheckpointRepository extends JpaRepository<Checkpoint, String> {
}
