package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.ToolCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface ToolCallRepository extends JpaRepository<ToolCall, String> {
    List<ToolCall> findByRunId(String runId);

    Optional<ToolCall> findByRunIdAndProviderCallId(String runId, String providerCallId);
}
