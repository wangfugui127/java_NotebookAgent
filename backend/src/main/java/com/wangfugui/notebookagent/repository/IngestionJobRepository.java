package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional
public interface IngestionJobRepository extends JpaRepository<IngestionJob, String> {
    Optional<IngestionJob> findByDocumentVersionId(String documentVersionId);
}
