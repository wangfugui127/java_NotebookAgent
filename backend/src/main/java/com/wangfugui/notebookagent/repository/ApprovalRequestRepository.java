package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalRequest a where a.id = :id")
    Optional<ApprovalRequest> lockById(@Param("id") String id);
    List<ApprovalRequest> findByRunId(String runId);

    Optional<ApprovalRequest> findByRunIdAndToolCallId(String runId, String toolCallId);
}
