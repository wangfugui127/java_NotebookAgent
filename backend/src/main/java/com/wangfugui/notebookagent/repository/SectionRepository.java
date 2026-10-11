package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface SectionRepository extends JpaRepository<Section, String> {
    List<Section> findByDocumentVersionIdOrderByOrdinal(String documentVersionId);

    void deleteByDocumentVersionId(String documentVersionId);
}
