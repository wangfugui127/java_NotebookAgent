-- NotebookAgent baseline schema. Mirrors the Python SQLAlchemy models plus the
-- task_execution table that backs the reliable Redis queue/outbox.
-- JSON columns are stored as MySQL JSON and mapped to String in Java.

CREATE TABLE users (
    id            VARCHAR(36)  NOT NULL,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    display_name  VARCHAR(120) NOT NULL DEFAULT '',
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    approval_mode VARCHAR(16)  NOT NULL DEFAULT 'confirm',
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_tokens (
    id          VARCHAR(36) NOT NULL,
    user_id     VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    revoked_at  DATETIME(6) NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_refresh_fingerprint (fingerprint),
    KEY ix_refresh_user (user_id),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notebooks (
    id          VARCHAR(36)  NOT NULL,
    owner_id    VARCHAR(36)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    description TEXT         NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_notebooks_owner (owner_id),
    CONSTRAINT fk_notebooks_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE documents (
    id                VARCHAR(36)  NOT NULL,
    notebook_id       VARCHAR(36)  NOT NULL,
    title             VARCHAR(512) NOT NULL,
    media_type        VARCHAR(120) NOT NULL,
    active_version_id VARCHAR(36)  NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_documents_notebook (notebook_id),
    CONSTRAINT fk_documents_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_versions (
    id                  VARCHAR(36)   NOT NULL,
    document_id         VARCHAR(36)   NOT NULL,
    version_number      INT           NOT NULL DEFAULT 1,
    storage_path        VARCHAR(1024) NOT NULL,
    content_hash        VARCHAR(64)   NOT NULL,
    language            VARCHAR(24)   NOT NULL DEFAULT 'default',
    status              VARCHAR(40)   NOT NULL DEFAULT 'pending',
    graph_status        VARCHAR(24)   NOT NULL DEFAULT 'pending',
    full_text           LONGTEXT      NULL,
    normalized_markdown LONGTEXT      NULL,
    parser_name         VARCHAR(64)   NOT NULL DEFAULT '',
    parser_version      VARCHAR(24)   NOT NULL DEFAULT '1',
    error_message       TEXT          NULL,
    created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_version_number (document_id, version_number),
    KEY ix_versions_document (document_id),
    KEY ix_versions_status (status),
    KEY ix_versions_graph_status (graph_status),
    CONSTRAINT fk_versions_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE documents
    ADD CONSTRAINT fk_documents_active_version FOREIGN KEY (active_version_id) REFERENCES document_versions (id);

CREATE TABLE sections (
    id                  VARCHAR(36)  NOT NULL,
    document_version_id VARCHAR(36)  NOT NULL,
    title               VARCHAR(512) NOT NULL DEFAULT '',
    ordinal             INT          NOT NULL,
    page_start          INT          NULL,
    page_end            INT          NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_sections_version (document_version_id),
    CONSTRAINT fk_sections_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_blocks (
    id                  VARCHAR(36) NOT NULL,
    document_version_id VARCHAR(36) NOT NULL,
    section_id          VARCHAR(36) NULL,
    ordinal             INT         NOT NULL,
    block_type          VARCHAR(24) NOT NULL,
    text                LONGTEXT    NOT NULL,
    markdown            LONGTEXT    NOT NULL,
    page_number         INT         NULL,
    bbox                JSON        NOT NULL,
    char_start          INT         NOT NULL DEFAULT 0,
    char_end            INT         NOT NULL DEFAULT 0,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_block_ordinal (document_version_id, ordinal),
    KEY ix_blocks_type (block_type),
    CONSTRAINT fk_blocks_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_blocks_section FOREIGN KEY (section_id) REFERENCES sections (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chunks (
    id                  VARCHAR(36) NOT NULL,
    document_version_id VARCHAR(36) NOT NULL,
    section_id          VARCHAR(36) NULL,
    ordinal             INT         NOT NULL,
    page_start          INT         NULL,
    page_end            INT         NULL,
    char_start          INT         NOT NULL DEFAULT 0,
    char_end            INT         NOT NULL DEFAULT 0,
    content             LONGTEXT    NOT NULL,
    content_hash        VARCHAR(64) NOT NULL,
    chunk_type          VARCHAR(24) NOT NULL DEFAULT 'paragraph',
    block_ids           JSON        NOT NULL,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_chunk_ordinal (document_version_id, ordinal),
    KEY ix_chunks_version (document_version_id),
    KEY ix_chunks_type (chunk_type),
    KEY ix_chunks_active (is_active),
    CONSTRAINT fk_chunks_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ingestion_jobs (
    id                  VARCHAR(36)  NOT NULL,
    document_version_id VARCHAR(36)  NOT NULL,
    status              VARCHAR(40)  NOT NULL DEFAULT 'pending',
    progress            INT          NOT NULL DEFAULT 0,
    idempotency_key     VARCHAR(128) NOT NULL,
    error_message       TEXT         NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ingestion_version (document_version_id),
    UNIQUE KEY uq_ingestion_idem (idempotency_key),
    KEY ix_ingestion_status (status),
    CONSTRAINT fk_ingestion_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversations (
    id          VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    notebook_id VARCHAR(36)  NOT NULL,
    title       VARCHAR(255) NOT NULL DEFAULT 'New conversation',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_conversations_user (user_id),
    KEY ix_conversations_notebook (notebook_id),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversations_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversation_resource_selections (
    id              VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    document_id     VARCHAR(36) NOT NULL,
    mode            VARCHAR(16) NOT NULL DEFAULT 'summary',
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_selection (conversation_id, document_id),
    KEY ix_selection_conversation (conversation_id),
    CONSTRAINT fk_selection_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_selection_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE messages (
    id              VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    role            VARCHAR(24) NOT NULL,
    content         LONGTEXT    NOT NULL,
    attachments     JSON        NOT NULL,
    provider_state  JSON        NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_messages_conversation (conversation_id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversation_summaries (
    id                  VARCHAR(36) NOT NULL,
    conversation_id     VARCHAR(36) NOT NULL,
    through_message_id  VARCHAR(36) NOT NULL,
    summary             JSON        NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_summaries_conversation (conversation_id),
    CONSTRAINT fk_summaries_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_runs (
    id              VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    user_id         VARCHAR(36) NOT NULL,
    notebook_id     VARCHAR(36) NOT NULL,
    query           LONGTEXT    NOT NULL,
    attachment_ids  JSON        NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    state           JSON        NOT NULL,
    error_message   TEXT        NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_runs_conversation (conversation_id),
    KEY ix_runs_user (user_id),
    KEY ix_runs_notebook (notebook_id),
    KEY ix_runs_status (status),
    CONSTRAINT fk_runs_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_runs_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_items (
    id         VARCHAR(36)  NOT NULL,
    run_id     VARCHAR(36)  NOT NULL,
    title      VARCHAR(512) NOT NULL,
    status     VARCHAR(32)  NOT NULL DEFAULT 'pending',
    ordinal    INT          NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_task_items_run (run_id),
    CONSTRAINT fk_task_items_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tool_calls (
    id               VARCHAR(36)  NOT NULL,
    run_id           VARCHAR(36)  NOT NULL,
    provider_call_id VARCHAR(255) NOT NULL,
    tool_name        VARCHAR(255) NOT NULL,
    arguments        JSON         NOT NULL,
    arguments_hash   VARCHAR(64)  NOT NULL,
    risk             VARCHAR(24)  NOT NULL DEFAULT 'read',
    status           VARCHAR(32)  NOT NULL DEFAULT 'pending',
    result           JSON         NULL,
    error_message    TEXT         NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tool_call (run_id, provider_call_id),
    KEY ix_tool_calls_run (run_id),
    KEY ix_tool_calls_provider (provider_call_id),
    CONSTRAINT fk_tool_calls_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE run_events (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    run_id     VARCHAR(36)  NOT NULL,
    sequence   INT          NOT NULL,
    event_type VARCHAR(80)  NOT NULL,
    payload    JSON         NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_run_event (run_id, sequence),
    KEY ix_run_events_run (run_id),
    CONSTRAINT fk_run_events_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE checkpoints (
    id         VARCHAR(36) NOT NULL,
    run_id     VARCHAR(36) NOT NULL,
    boundary   VARCHAR(80) NOT NULL,
    state      JSON        NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_checkpoints_run (run_id),
    CONSTRAINT fk_checkpoints_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence (
    id                VARCHAR(36)  NOT NULL,
    run_id            VARCHAR(36)  NOT NULL,
    chunk_id          VARCHAR(36)  NULL,
    source_kind       VARCHAR(32)  NOT NULL,
    source_id         VARCHAR(255) NOT NULL,
    text              LONGTEXT     NOT NULL,
    section_title     VARCHAR(512) NULL,
    page_start        INT          NULL,
    page_end          INT          NULL,
    char_start        INT          NULL,
    char_end          INT          NULL,
    block_ids         JSON         NOT NULL,
    retrieval_sources JSON         NOT NULL,
    score             DOUBLE       NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_evidence_run (run_id),
    CONSTRAINT fk_evidence_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE citations (
    id          VARCHAR(36) NOT NULL,
    message_id  VARCHAR(36) NOT NULL,
    evidence_id VARCHAR(36) NOT NULL,
    claim_text  TEXT        NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_citations_message (message_id),
    CONSTRAINT fk_citations_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_citations_evidence FOREIGN KEY (evidence_id) REFERENCES evidence (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_paper_results (
    id                VARCHAR(36)  NOT NULL,
    run_id            VARCHAR(36)  NOT NULL,
    provider          VARCHAR(40)  NOT NULL,
    provider_paper_id VARCHAR(255) NOT NULL,
    data              JSON         NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_external_run (run_id),
    CONSTRAINT fk_external_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_requests (
    id           VARCHAR(36) NOT NULL,
    run_id       VARCHAR(36) NOT NULL,
    tool_call_id VARCHAR(36) NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'pending',
    reason       TEXT        NOT NULL,
    mode         VARCHAR(16) NOT NULL DEFAULT 'confirm',
    tool_risk    VARCHAR(16) NOT NULL DEFAULT 'write',
    resolved_by  VARCHAR(36) NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_approvals_run (run_id),
    CONSTRAINT fk_approvals_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_approvals_tool_call FOREIGN KEY (tool_call_id) REFERENCES tool_calls (id) ON DELETE CASCADE,
    CONSTRAINT fk_approvals_user FOREIGN KEY (resolved_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_profiles (
    id                     VARCHAR(36) NOT NULL,
    document_version_id    VARCHAR(36) NOT NULL,
    document_id            VARCHAR(36) NOT NULL,
    notebook_id            VARCHAR(36) NOT NULL,
    authors                JSON        NOT NULL,
    year                   INT         NULL,
    keywords               JSON        NOT NULL,
    abstract               LONGTEXT    NULL,
    abstract_source        VARCHAR(16) NOT NULL DEFAULT 'missing',
    region                 VARCHAR(512) NULL,
    time_range             VARCHAR(255) NULL,
    metadata_json          JSON        NOT NULL,
    one_sentence           LONGTEXT    NOT NULL,
    data_and_method        LONGTEXT    NOT NULL,
    results_conclusion     LONGTEXT    NOT NULL,
    contribution_limitations LONGTEXT  NOT NULL,
    profile_text           LONGTEXT    NOT NULL,
    status                 VARCHAR(24) NOT NULL DEFAULT 'pending',
    extractor_model        VARCHAR(120) NOT NULL DEFAULT '',
    extractor_version      VARCHAR(24) NOT NULL DEFAULT '1',
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_profile_version (document_version_id),
    KEY ix_profile_document (document_id),
    KEY ix_profile_notebook (notebook_id),
    KEY ix_profile_status (status),
    CONSTRAINT fk_profile_version FOREIGN KEY (document_version_id) REFERENCES document_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE run_transcripts (
    run_id          VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    messages        JSON        NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id),
    KEY ix_transcripts_conversation (conversation_id),
    CONSTRAINT fk_transcripts_run FOREIGN KEY (run_id) REFERENCES agent_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_transcripts_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_datasets (
    id          VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT         NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_eval_datasets_user (user_id),
    CONSTRAINT fk_eval_datasets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_cases (
    id                    VARCHAR(36)  NOT NULL,
    dataset_id            VARCHAR(36)  NOT NULL,
    case_key              VARCHAR(120) NOT NULL,
    question              LONGTEXT     NOT NULL,
    expected_tools        JSON         NOT NULL,
    expected_source_titles JSON        NOT NULL,
    required_keywords     JSON         NOT NULL,
    ordinal               INT          NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_eval_case (dataset_id, case_key),
    KEY ix_eval_cases_dataset (dataset_id),
    CONSTRAINT fk_eval_cases_dataset FOREIGN KEY (dataset_id) REFERENCES eval_datasets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_runs (
    id                     VARCHAR(36)  NOT NULL,
    user_id                VARCHAR(36)  NOT NULL,
    notebook_id            VARCHAR(36)  NOT NULL,
    dataset_id             VARCHAR(36)  NULL,
    dataset_name           VARCHAR(255) NOT NULL DEFAULT '',
    status                 VARCHAR(24)  NOT NULL DEFAULT 'pending',
    total_cases            INT          NOT NULL DEFAULT 0,
    completed_cases        INT          NOT NULL DEFAULT 0,
    passed_cases           INT          NOT NULL DEFAULT 0,
    current_case_key       VARCHAR(120) NULL,
    current_agent_run_id   VARCHAR(36)  NULL,
    current_case_result_id VARCHAR(36)  NULL,
    score_config           JSON         NOT NULL,
    metrics                JSON         NOT NULL,
    error_message          TEXT         NULL,
    created_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_eval_runs_user (user_id),
    KEY ix_eval_runs_notebook (notebook_id),
    KEY ix_eval_runs_dataset (dataset_id),
    KEY ix_eval_runs_status (status),
    CONSTRAINT fk_eval_runs_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_eval_runs_notebook FOREIGN KEY (notebook_id) REFERENCES notebooks (id) ON DELETE CASCADE,
    CONSTRAINT fk_eval_runs_dataset FOREIGN KEY (dataset_id) REFERENCES eval_datasets (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE eval_case_results (
    id                    VARCHAR(36)  NOT NULL,
    eval_run_id           VARCHAR(36)  NOT NULL,
    case_key              VARCHAR(120) NOT NULL DEFAULT '',
    ordinal               INT          NOT NULL DEFAULT 0,
    question              LONGTEXT     NOT NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'pending',
    expected_tools        JSON         NOT NULL,
    actual_tools          JSON         NOT NULL,
    expected_source_titles JSON        NOT NULL,
    matched_sources       JSON         NOT NULL,
    missing_sources       JSON         NOT NULL,
    required_keywords     JSON         NOT NULL,
    matched_keywords      JSON         NOT NULL,
    answer                LONGTEXT     NULL,
    route_score           DOUBLE       NULL,
    retrieval_score       DOUBLE       NULL,
    citation_score        DOUBLE       NULL,
    keyword_score         DOUBLE       NULL,
    total_score           DOUBLE       NULL,
    passed                BOOLEAN      NOT NULL DEFAULT FALSE,
    latency_ms            INT          NULL,
    agent_run_id          VARCHAR(36)  NULL,
    error_message         TEXT         NULL,
    detail                JSON         NOT NULL,
    created_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_eval_results_run (eval_run_id),
    KEY ix_eval_results_status (status),
    CONSTRAINT fk_eval_results_run FOREIGN KEY (eval_run_id) REFERENCES eval_runs (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reliable task queue: transactional outbox + execution lease state.
CREATE TABLE task_execution (
    id             VARCHAR(36)  NOT NULL,
    task_type      VARCHAR(64)  NOT NULL,
    payload        JSON         NOT NULL,
    dedup_key      VARCHAR(191) NULL,
    status         VARCHAR(24)  NOT NULL DEFAULT 'NEW',
    attempt        INT          NOT NULL DEFAULT 0,
    max_attempts   INT          NOT NULL DEFAULT 1,
    lease_owner    VARCHAR(128) NULL,
    lease_until    DATETIME(6)  NULL,
    next_attempt_at DATETIME(6) NULL,
    stream_id      VARCHAR(64)  NULL,
    error_message  TEXT         NULL,
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_task_dedup (dedup_key),
    KEY ix_task_status (status),
    KEY ix_task_next_attempt (next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
