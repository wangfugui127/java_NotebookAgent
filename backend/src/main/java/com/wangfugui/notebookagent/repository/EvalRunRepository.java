package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface EvalRunRepository extends JpaRepository<EvalRun, String> {
    List<EvalRun> findByUserIdOrderByCreatedAtDesc(String userId);

    List<EvalRun> findByUserIdAndNotebookIdOrderByCreatedAtDesc(String userId, String notebookId);

    Optional<EvalRun> findFirstByDatasetIdAndUserIdOrderByCreatedAtDesc(String datasetId, String userId);

    Optional<EvalRun> findByIdAndUserId(String id, String userId);
}
