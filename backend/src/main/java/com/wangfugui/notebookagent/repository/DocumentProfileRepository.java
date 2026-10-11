package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.DocumentProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional
public interface DocumentProfileRepository extends JpaRepository<DocumentProfile, String> {
    Optional<DocumentProfile> findByDocumentVersionId(String documentVersionId);

    void deleteByDocumentVersionId(String documentVersionId);
}
