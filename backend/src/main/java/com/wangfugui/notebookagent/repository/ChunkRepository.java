package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Transactional
public interface ChunkRepository extends JpaRepository<Chunk, String> {
    List<Chunk> findByDocumentVersionIdAndIsActiveTrueOrderByOrdinal(String documentVersionId);

    List<Chunk> findByDocumentVersionIdOrderByOrdinal(String documentVersionId);

    List<Chunk> findByIdInAndIsActiveTrue(Collection<String> ids);

    void deleteByDocumentVersionId(String documentVersionId);
}
