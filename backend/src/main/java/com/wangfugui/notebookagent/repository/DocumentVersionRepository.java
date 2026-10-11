package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, String> {
    List<DocumentVersion> findByDocumentId(String documentId);

    List<DocumentVersion> findByStatus(String status);

    long countByDocumentId(String documentId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumberDesc(String documentId);
}
