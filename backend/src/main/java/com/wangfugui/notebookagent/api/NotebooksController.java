package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.api.dto.Dtos.NotebookCreate;
import com.wangfugui.notebookagent.api.dto.Dtos.NotebookUpdate;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.Notebook;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.ingestion.DeleteService;
import com.wangfugui.notebookagent.ingestion.SourceService;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebooks")
public class NotebooksController {

    private final NotebookRepository notebooks;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final SourceService sourceService;
    private final DeleteService deleteService;

    public NotebooksController(NotebookRepository notebooks, DocumentRepository documents,
                               DocumentVersionRepository versions, SourceService sourceService,
                               DeleteService deleteService) {
        this.notebooks = notebooks;
        this.documents = documents;
        this.versions = versions;
        this.sourceService = sourceService;
        this.deleteService = deleteService;
    }

    private Notebook owned(String notebookId, String userId) {
        return notebooks.findByIdAndOwnerId(notebookId, userId)
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
    }

    @GetMapping
    public List<Map<String, Object>> list(@CurrentUser User user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Notebook notebook : notebooks.findByOwnerIdOrderByCreatedAtDesc(user.getId())) {
            out.add(view(notebook));
        }
        return out;
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody NotebookCreate body, @CurrentUser User user) {
        Notebook notebook = new Notebook();
        notebook.setOwnerId(user.getId());
        notebook.setTitle(body.title());
        notebook.setDescription(body.description() == null ? "" : body.description());
        notebooks.save(notebook);
        return view(notebook);
    }

    @PatchMapping("/{notebookId}")
    @Transactional
    public Map<String, Object> update(@PathVariable String notebookId, @Valid @RequestBody NotebookUpdate body,
                                      @CurrentUser User user) {
        Notebook notebook = owned(notebookId, user.getId());
        if (body.title() != null) {
            notebook.setTitle(body.title());
        }
        if (body.description() != null) {
            notebook.setDescription(body.description());
        }
        notebooks.save(notebook);
        return view(notebook);
    }

    @DeleteMapping("/{notebookId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String notebookId, @CurrentUser User user) {
        owned(notebookId, user.getId());
        for (Document document : documents.findByNotebookId(notebookId)) {
            deleteService.deleteSource(document);
        }
        notebooks.deleteById(notebookId);
    }

    @GetMapping("/{notebookId}/documents")
    public Map<String, Object> listDocuments(@PathVariable String notebookId, @CurrentUser User user) {
        owned(notebookId, user.getId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document document : documents.findByNotebookId(notebookId)) {
            DocumentVersion active = document.getActiveVersionId() == null ? null
                    : versions.findById(document.getActiveVersionId()).orElse(null);
            if (active == null) active = versions.findFirstByDocumentIdOrderByVersionNumberDesc(document.getId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.getId());
            row.put("title", document.getTitle());
            row.put("media_type", document.getMediaType());
            row.put("version_id", active == null ? null : active.getId());
            row.put("status", active == null ? "pending" : active.getStatus());
            row.put("graph_status", active == null ? "pending" : active.getGraphStatus());
            row.put("error_message", active == null ? null : active.getErrorMessage());
            items.add(row);
        }
        return Map.of("items", items);
    }

    @PostMapping("/{notebookId}/documents")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> upload(@PathVariable String notebookId, @RequestParam("file") MultipartFile file,
                                      @CurrentUser User user) throws IOException {
        owned(notebookId, user.getId());
        byte[] content = file.getBytes();
        if (content.length == 0) {
            throw ApiException.badRequest("empty file");
        }
        if (content.length > 100L * 1024 * 1024) {
            throw new ApiException(413, "file exceeds 100 MB");
        }
        String suffix = "";
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            suffix = name.substring(dot);
        }
        SourceService.Created created = sourceService.createSource(notebookId, name,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(), content, suffix);
        return Map.of("document_id", created.documentId(), "version_id", created.versionId(), "job_id", created.jobId());
    }

    private Map<String, Object> view(Notebook notebook) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", notebook.getId());
        map.put("title", notebook.getTitle());
        map.put("description", notebook.getDescription());
        map.put("created_at", notebook.getCreatedAt());
        return map;
    }
}
