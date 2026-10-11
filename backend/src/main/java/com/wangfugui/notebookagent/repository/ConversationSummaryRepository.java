package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.ConversationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional
public interface ConversationSummaryRepository extends JpaRepository<ConversationSummary, String> {
    Optional<ConversationSummary> findFirstByConversationIdOrderByCreatedAtDesc(String conversationId);
}
