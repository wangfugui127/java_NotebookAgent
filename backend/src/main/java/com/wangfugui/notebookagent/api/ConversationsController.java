package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.api.dto.Dtos.ConversationCreate;
import com.wangfugui.notebookagent.api.dto.Dtos.ResourceSelectionUpdate;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.Citation;
import com.wangfugui.notebookagent.domain.Conversation;
import com.wangfugui.notebookagent.domain.ConversationResourceSelection;
import com.wangfugui.notebookagent.domain.Evidence;
import com.wangfugui.notebookagent.domain.Message;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.CitationRepository;
import com.wangfugui.notebookagent.repository.ConversationRepository;
import com.wangfugui.notebookagent.repository.ConversationResourceSelectionRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.EvidenceRepository;
import com.wangfugui.notebookagent.repository.MessageRepository;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationsController {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final CitationRepository citations;
    private final EvidenceRepository evidence;
    private final ConversationResourceSelectionRepository selections;
    private final DocumentRepository documents;
    private final NotebookRepository notebooks;
    private final AgentRunRepository runs;

    public ConversationsController(ConversationRepository conversations, MessageRepository messages,
                                   CitationRepository citations, EvidenceRepository evidence,
                                   ConversationResourceSelectionRepository selections, DocumentRepository documents,
                                   NotebookRepository notebooks, AgentRunRepository runs) {
        this.conversations = conversations;
        this.messages = messages;
        this.citations = citations;
        this.evidence = evidence;
        this.selections = selections;
        this.documents = documents;
        this.notebooks = notebooks;
        this.runs = runs;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam("notebook_id") String notebookId, @CurrentUser User user) {
        notebooks.findByIdAndOwnerId(notebookId, user.getId())
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Conversation conversation : conversations.findByNotebookIdAndUserIdOrderByUpdatedAtDesc(notebookId, user.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", conversation.getId());
            row.put("title", conversation.getTitle());
            row.put("updated_at", conversation.getUpdatedAt());
            items.add(row);
        }
        return Map.of("items", items);
    }

    @GetMapping("/{conversationId}/messages")
    public Map<String, Object> listMessages(@PathVariable String conversationId, @CurrentUser User user) {
        Conversation conversation = conversations.findByIdAndUserId(conversationId, user.getId())
                .orElseThrow(() -> ApiException.notFound("conversation not found"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Message message : messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getId());
            row.put("role", message.getRole());
            row.put("content", message.getContent());
            row.put("attachments", com.wangfugui.notebookagent.core.Json.readList(message.getAttachments()));
            row.put("created_at", message.getCreatedAt());
            List<Map<String, Object>> citationItems = new ArrayList<>();
            for (Citation citation : citations.findByMessageId(message.getId())) {
                Evidence ev = evidence.findById(citation.getEvidenceId()).orElse(null);
                if (ev == null) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("evidence_id", ev.getId());
                c.put("chunk_id", ev.getChunkId());
                c.put("source_kind", ev.getSourceKind());
                c.put("source_id", ev.getSourceId());
                c.put("page_start", ev.getPageStart());
                c.put("page_end", ev.getPageEnd());
                c.put("char_start", ev.getCharStart());
                c.put("char_end", ev.getCharEnd());
                citationItems.add(c);
            }
            row.put("citations", citationItems);
            items.add(row);
        }
        return Map.of("items", items);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@Valid @RequestBody ConversationCreate body, @CurrentUser User user) {
        notebooks.findByIdAndOwnerId(body.notebookId(), user.getId())
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
        Conversation conversation = new Conversation();
        conversation.setUserId(user.getId());
        conversation.setNotebookId(body.notebookId());
        conversation.setTitle(body.title() == null || body.title().isBlank() ? "New conversation" : body.title());
        conversations.save(conversation);
        for (var document : documents.findByNotebookId(body.notebookId())) {
            ConversationResourceSelection selection = new ConversationResourceSelection();
            selection.setConversationId(conversation.getId());
            selection.setDocumentId(document.getId());
            selection.setMode("summary");
            selections.save(selection);
        }
        return Map.of("id", conversation.getId());
    }

    @DeleteMapping("/{conversationId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId, @CurrentUser User user) {
        conversations.findByIdAndUserId(conversationId, user.getId())
                .orElseThrow(() -> ApiException.notFound("conversation not found"));
        boolean active = !runs.findByConversationIdAndStatusIn(conversationId,
                List.of("pending", "running", "waiting_approval")).isEmpty();
        if (active) {
            throw ApiException.conflict("对话正在运行，请先停止");
        }
        conversations.deleteById(conversationId);
    }

    @PutMapping("/{conversationId}/resources/{documentId}")
    @Transactional
    public Map<String, String> setResourceMode(@PathVariable String conversationId, @PathVariable String documentId,
                                               @Valid @RequestBody ResourceSelectionUpdate body, @CurrentUser User user) {
        Conversation conversation = conversations.findByIdAndUserId(conversationId, user.getId())
                .orElseThrow(() -> ApiException.notFound("conversation not found"));
        documents.findByIdAndNotebookId(documentId, conversation.getNotebookId())
                .orElseThrow(() -> ApiException.notFound("document not found"));
        ConversationResourceSelection selection = selections
                .findByConversationIdAndDocumentId(conversationId, documentId).orElseGet(() -> {
                    ConversationResourceSelection s = new ConversationResourceSelection();
                    s.setConversationId(conversationId);
                    s.setDocumentId(documentId);
                    return s;
                });
        selection.setMode(body.mode());
        selections.save(selection);
        return Map.of("mode", body.mode());
    }
}
