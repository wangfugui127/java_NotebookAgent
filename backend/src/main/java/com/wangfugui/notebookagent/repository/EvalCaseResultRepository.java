package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.EvalCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface EvalCaseResultRepository extends JpaRepository<EvalCaseResult, String> {
    List<EvalCaseResult> findByEvalRunIdOrderByOrdinalAsc(String evalRunId);
}
