package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Notebook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface NotebookRepository extends JpaRepository<Notebook, String> {
    List<Notebook> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Optional<Notebook> findByIdAndOwnerId(String id, String ownerId);
}
