package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional
public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByConversationIdOrderByCreatedAtAscIdAsc(String conversationId);

    List<Message> findByConversationIdAndRoleInOrderByCreatedAtAscIdAsc(String conversationId, List<String> roles);

    long countByConversationId(String conversationId);
}
