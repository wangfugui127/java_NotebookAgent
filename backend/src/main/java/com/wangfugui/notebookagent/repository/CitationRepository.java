package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Citation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Transactional
public interface CitationRepository extends JpaRepository<Citation, String> {
    List<Citation> findByMessageId(String messageId);

    void deleteByEvidenceIdIn(Collection<String> evidenceIds);
}
