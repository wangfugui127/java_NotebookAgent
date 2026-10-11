package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.RunTranscript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public interface RunTranscriptRepository extends JpaRepository<RunTranscript, String> {
}
