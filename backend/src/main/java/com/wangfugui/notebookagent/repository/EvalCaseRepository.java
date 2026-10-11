package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.EvalCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface EvalCaseRepository extends JpaRepository<EvalCase, String> {
    List<EvalCase> findByDatasetIdOrderByOrdinalAscCreatedAtAsc(String datasetId);

    long countByDatasetId(String datasetId);
}
