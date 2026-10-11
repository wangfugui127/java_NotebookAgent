package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.EvalDataset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface EvalDatasetRepository extends JpaRepository<EvalDataset, String> {
    List<EvalDataset> findByUserIdOrderByCreatedAtDesc(String userId);
}
