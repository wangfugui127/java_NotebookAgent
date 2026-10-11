package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByNotebookId(String notebookId);

    Optional<Document> findByIdAndNotebookId(String id, String notebookId);
}
