package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.ExternalPaperResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface ExternalPaperResultRepository extends JpaRepository<ExternalPaperResult, String> {
}
