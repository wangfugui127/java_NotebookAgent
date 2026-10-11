package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.ConversationResourceSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional
public interface ConversationResourceSelectionRepository
        extends JpaRepository<ConversationResourceSelection, String> {
    List<ConversationResourceSelection> findByConversationId(String conversationId);

    List<ConversationResourceSelection> findByConversationIdAndModeNot(String conversationId, String mode);

    Optional<ConversationResourceSelection> findByConversationIdAndDocumentId(String conversationId, String documentId);
}
