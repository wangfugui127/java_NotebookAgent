package com.wangfugui.notebookagent.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/** Request/response payloads (serialized as snake_case by the global Jackson config). */
public final class Dtos {

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 256) String password,
            @Size(max = 120) String displayName) {
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType) {
        public static TokenResponse of(String access, String refresh) {
            return new TokenResponse(access, refresh, "bearer");
        }
    }

    public record UserSettingsUpdate(@NotBlank @Pattern(regexp = "read_only|confirm|auto") String approvalMode) {
    }

    public record NotebookCreate(@NotBlank @Size(max = 255) String title, String description) {
    }

    public record NotebookUpdate(@Size(min = 1, max = 255) String title, String description) {
    }

    public record ConversationCreate(@NotBlank String notebookId, String title) {
    }

    public record ResourceSelectionUpdate(@NotBlank @Pattern(regexp = "off|summary|full") String mode) {
    }

    public record AgentRunCreate(
            @NotBlank String conversationId,
            @NotBlank String query,
            List<String> attachmentIds,
            Map<String, String> attachmentInstructions,
            @Pattern(regexp = "read_only|confirm|auto") String approvalMode) {
    }

    public record ApprovalResolve(@NotBlank @Pattern(regexp = "approved|rejected") String decision) {
    }

    public record EvalCaseInput(
            String caseKey,
            @NotBlank String question,
            List<String> expectedTools,
            List<String> expectedSourceTitles,
            List<String> requiredKeywords) {
    }

    public record EvalCaseUpdate(
            String caseKey,
            String question,
            List<String> expectedTools,
            List<String> expectedSourceTitles,
            List<String> requiredKeywords) {
    }

    public record EvalDatasetCreate(@NotBlank @Size(max = 255) String name, String description) {
    }

    public record EvalDatasetUpdate(@Size(min = 1, max = 255) String name, String description) {
    }

    public record EvalImportRequest(List<@Valid EvalCaseInput> cases, boolean replace) {
    }

    public record EvalRunCreate(@NotBlank String notebookId, boolean allowMissing) {
    }

    private Dtos() {
    }
}
