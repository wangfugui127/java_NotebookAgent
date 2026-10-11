package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Evidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Transactional
public interface EvidenceRepository extends JpaRepository<Evidence, String> {
    Optional<Evidence> findFirstByRunIdAndChunkIdAndSourceKind(String runId, String chunkId, String sourceKind);
    List<Evidence> findByIdInAndRunId(Collection<String> ids, String runId);

    List<Evidence> findByIdIn(Collection<String> ids);

    List<Evidence> findByRunId(String runId);

    List<Evidence> findByChunkIdIn(Collection<String> chunkIds);
}
