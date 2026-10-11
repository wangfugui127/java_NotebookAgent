package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.DocumentBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface DocumentBlockRepository extends JpaRepository<DocumentBlock, String> {
    List<DocumentBlock> findByDocumentVersionIdOrderByOrdinal(String documentVersionId);

    void deleteByDocumentVersionId(String documentVersionId);
}
