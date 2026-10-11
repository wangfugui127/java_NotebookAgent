package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Transactional
public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentRun r where r.id = :id")
    Optional<AgentRun> lockById(@Param("id") String id);
    Optional<AgentRun> findByIdAndUserId(String id, String userId);

    List<AgentRun> findByConversationIdAndStatusIn(String conversationId, List<String> statuses);
}
