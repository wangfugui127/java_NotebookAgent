package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.AgentRun;
import com.wangfugui.notebookagent.domain.Conversation;
import com.wangfugui.notebookagent.domain.Message;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.queue.TaskQueue;
import com.wangfugui.notebookagent.repository.AgentRunRepository;
import com.wangfugui.notebookagent.repository.MessageRepository;
import com.wangfugui.notebookagent.repository.ConversationRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.core.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates an agent run, persists the user message, and enqueues execution. */
@Service
public class RunService {

    @Autowired private ConversationRepository conversations;
    @Autowired private DocumentRepository documents;
    private final AgentRunRepository runs;
    private final MessageRepository messages;
    private final TaskQueue tasks;

    public RunService(AgentRunRepository runs, MessageRepository messages, TaskQueue tasks) {
        this.runs = runs;
        this.messages = messages;
        this.tasks = tasks;
    }

    @Transactional
    public AgentRun createRun(User user, Conversation conversation, String query, List<String> attachmentIds,
                              Map<String, String> attachmentInstructions, String approvalMode) {
        for (String id : attachmentIds == null ? List.<String>of() : attachmentIds) {
            if (documents.findByIdAndNotebookId(id, conversation.getNotebookId()).isEmpty()) throw ApiException.notFound("attachment not found");
        }
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setRole("user");
        message.setContent(query);
        message.setAttachments(Json.write(attachmentIds == null ? List.of() : attachmentIds));
        messages.save(message);

        long count = messages.countByConversationId(conversation.getId());
        if (count <= 1 && (conversation.getTitle() == null || conversation.getTitle().isBlank()
                || "New conversation".equals(conversation.getTitle()) || "研究对话".equals(conversation.getTitle()))) {
            String trimmed = query.strip();
            conversation.setTitle(trimmed.length() > 30 ? trimmed.substring(0, 30) : trimmed);
        }

        conversations.save(conversation);
        String resolvedMode = approvalMode == null || approvalMode.isBlank() ? user.getApprovalMode() : approvalMode;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("attachment_instructions", attachmentInstructions == null ? Map.of() : attachmentInstructions);
        state.put("input_message_id", message.getId());
        state.put("approval_mode", resolvedMode);

        AgentRun run = new AgentRun();
        run.setConversationId(conversation.getId());
        run.setUserId(user.getId());
        run.setNotebookId(conversation.getNotebookId());
        run.setQuery(query);
        run.setAttachmentIds(Json.write(attachmentIds == null ? List.of() : attachmentIds));
        run.setStatus("pending");
        run.setState(Json.write(state));
        runs.save(run);

        tasks.enqueue("execute_agent_run", Map.of("run_id", run.getId()), "run:" + run.getId(), 3);
        return run;
    }
}
