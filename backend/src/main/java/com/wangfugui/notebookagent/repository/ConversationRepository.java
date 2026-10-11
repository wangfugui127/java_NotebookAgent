package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface ConversationRepository extends JpaRepository<Conversation, String> {
    List<Conversation> findByNotebookIdAndUserIdOrderByUpdatedAtDesc(String notebookId, String userId);

    Optional<Conversation> findByIdAndUserId(String id, String userId);
}
