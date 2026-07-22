-- ----------------------------------------------------
-- Migration script for Score v3.6.0                 --
--                                                   --
-- Author: Hakju Oh <hakju.oh@nist.gov>              --
-- ----------------------------------------------------

CREATE TABLE `ai_chat_conversation`
(
    `ai_chat_conversation_id`        bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the AI chat conversation record.',
    `guid`                           char(36) COLLATE utf8mb4_bin NOT NULL COMMENT 'The public unique identifier of the AI chat conversation.',
    `app_user_id`                    bigint(20) unsigned NOT NULL COMMENT 'Foreign key to the APP_USER table identifying the owner of the conversation.',
    `parent_ai_chat_conversation_id` bigint(20) unsigned NULL COMMENT 'Self-reference to the root conversation that owns this child execution.',
    `conversation_kind`              varchar(16)                  NULL DEFAULT 'ROOT' COMMENT 'Expected conversation kinds are ROOT, SUBAGENT, and PARALLEL; other values are handled by the application.',
    `agent_id`                       varchar(64) NULL COMMENT 'Registered worker identifier for a child execution conversation.',
    `parent_request_id`              varchar(128) COLLATE utf8mb4_bin NULL COMMENT 'Root request that created this child execution conversation.',
    `title`                          varchar(240)                 NOT NULL COMMENT 'The display title of the conversation, derived from its first prompt.',
    `compacted`                      tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Indicates whether the conversation model context has been compacted (0 = False, 1 = True).',
    `created_at`                     datetime(6) NOT NULL COMMENT 'The timestamp when the conversation was created.',
    `updated_at`                     datetime(6) NOT NULL COMMENT 'The timestamp of the most recent activity in the conversation.',
    PRIMARY KEY (`ai_chat_conversation_id`),
    UNIQUE KEY `ai_chat_conversation_guid_uk` (`guid`),
    KEY                              `ai_chat_conversation_owner_updated_idx` (`app_user_id`, `updated_at`),
    KEY                              `ai_chat_conversation_parent_idx` (`parent_ai_chat_conversation_id`, `created_at`),
    KEY                              `ai_chat_conversation_parent_request_idx` (`parent_ai_chat_conversation_id`, `parent_request_id`),
    CONSTRAINT `ai_chat_conversation_app_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_chat_conversation_parent_fk`
        FOREIGN KEY (`parent_ai_chat_conversation_id`)
            REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='User-owned AI chat conversation metadata.';

CREATE TABLE `ai_chat_memory`
(
    `ai_chat_memory_id`       bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the AI chat memory record.',
    `ai_chat_conversation_id` bigint(20) unsigned NOT NULL COMMENT 'Foreign key to the AI_CHAT_CONVERSATION table identifying the owning conversation.',
    `memory_sequence`         bigint      NOT NULL COMMENT 'The zero-based sequence of the message in the bounded model context.',
    `message_type`            varchar(16) NULL COMMENT 'Expected model-context roles are USER, ASSISTANT, SYSTEM, and TOOL; other values are handled by the application.',
    `content`                 longtext    NOT NULL COMMENT 'The text content supplied to the model as conversation memory.',
    `metadata_json`           JSON NULL COMMENT 'Optional JSON metadata associated with the model-context message.',
    `created_at`              datetime(6) NOT NULL COMMENT 'The timestamp when the memory record was created.',
    PRIMARY KEY (`ai_chat_memory_id`),
    UNIQUE KEY `ai_chat_memory_conversation_sequence_uk` (`ai_chat_conversation_id`, `memory_sequence`),
    CONSTRAINT `ai_chat_memory_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Bounded connectCenter assistant model context.';

CREATE TABLE `ai_chat_step`
(
    `ai_chat_step_id`         bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the AI chat trajectory step record.',
    `ai_chat_conversation_id` bigint(20) unsigned NOT NULL COMMENT 'Foreign key to the AI_CHAT_CONVERSATION table identifying the owning conversation.',
    `step_sequence`           bigint      NOT NULL COMMENT 'The zero-based sequence of the step within the complete conversation trajectory.',
    `request_id`              varchar(128) COLLATE utf8mb4_bin NULL COMMENT 'The request identifier used to correlate steps produced by the same chat request.',
    `source`                  varchar(16) NULL DEFAULT 'system' COMMENT 'Expected ATIF sources are system, user, and agent; other values are handled by the application.',
    `message_kind`            varchar(64) NOT NULL COMMENT 'The application-specific kind of trajectory event represented by the step.',
    `visibility`              varchar(16) NULL DEFAULT 'visible' COMMENT 'Expected presentation scopes are visible and debug; other values are handled by the application.',
    `message`                 longtext    NOT NULL COMMENT 'The textual message or event content recorded for the step.',
    `reasoning_content`       longtext NULL COMMENT 'Optional model reasoning content associated with the step.',
    `model_name`              varchar(240) NULL COMMENT 'The model used for the inference represented by the step.',
    `reasoning_effort`        varchar(32) NULL COMMENT 'The reasoning-effort setting used for the model inference.',
    `tool_calls_json`         JSON NULL COMMENT 'JSON array containing tool calls requested by the model.',
    `observation_json`        JSON NULL COMMENT 'JSON object containing tool execution results observed by the agent.',
    `metrics_json`            JSON NULL COMMENT 'JSON object containing token usage and other execution metrics.',
    `extra_json`              JSON NULL COMMENT 'JSON object containing additional ATIF or application-specific step metadata.',
    `llm_call_count`          int NULL COMMENT 'The number of language-model calls represented by the step.',
    `is_copied_context`       tinyint(1) NULL COMMENT 'Indicates whether the step was copied into the model context (0 = False, 1 = True).',
    `created_at`              datetime(6) NOT NULL COMMENT 'The timestamp when the trajectory step was created.',
    PRIMARY KEY (`ai_chat_step_id`),
    UNIQUE KEY `ai_chat_step_conversation_sequence_uk` (`ai_chat_conversation_id`, `step_sequence`),
    KEY                       `ai_chat_step_request_idx` (`ai_chat_conversation_id`, `request_id`),
    KEY                       `ai_chat_step_settings_idx` (`ai_chat_conversation_id`, `message_kind`, `step_sequence`),
    KEY                       `ai_chat_step_created_idx` (`ai_chat_conversation_id`, `created_at`),
    CONSTRAINT `ai_chat_step_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Complete connectCenter assistant trajectory in ATIF-reconstructable steps.';

CREATE TABLE `ai_chat_mutation_confirmation`
(
    `ai_chat_mutation_confirmation_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the AI mutation confirmation record.',
    `guid`                             char(36) COLLATE utf8mb4_bin     NOT NULL COMMENT 'The public unique identifier of the mutation confirmation request.',
    `ai_chat_conversation_id`          bigint(20) unsigned NOT NULL COMMENT 'Foreign key to the AI_CHAT_CONVERSATION table identifying the owning conversation.',
    `request_id`                       varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'The chat request identifier that originally requested the data-changing tool call.',
    `tool_name`                        varchar(240)                     NOT NULL COMMENT 'The name of the data-changing tool that requires explicit user approval.',
    `arguments_digest`                 char(64) COLLATE ascii_bin       NOT NULL COMMENT 'The SHA-256 digest binding the tool name to its canonicalized arguments.',
    `status`                           varchar(16)                      NULL DEFAULT 'REQUESTED' COMMENT 'Expected confirmation states are REQUESTED, APPROVED, DENIED, CONSUMED, and EXPIRED; other values are handled by the application.',
    `grant_digest`                     char(64) COLLATE ascii_bin NULL COMMENT 'The SHA-256 digest of the one-time approval grant; cleared after consumption, denial, or expiration.',
    `expires_at`                       datetime(6) NOT NULL COMMENT 'The timestamp after which the confirmation request or approval grant is invalid.',
    `approved_at`                      datetime(6) NULL COMMENT 'The timestamp when the mutation request was approved.',
    `denied_at`                        datetime(6) NULL COMMENT 'The timestamp when the mutation request was denied.',
    `consumed_at`                      datetime(6) NULL COMMENT 'The timestamp when the one-time approval grant was consumed.',
    `expired_at`                       datetime(6) NULL COMMENT 'The timestamp when the confirmation request or approval grant expired.',
    `created_at`                       datetime(6) NOT NULL COMMENT 'The timestamp when the mutation confirmation request was created.',
    PRIMARY KEY (`ai_chat_mutation_confirmation_id`),
    UNIQUE KEY `ai_chat_mutation_confirmation_guid_uk` (`guid`),
    UNIQUE KEY `ai_chat_mutation_confirmation_grant_uk` (`grant_digest`),
    KEY                                `ai_chat_mutation_confirmation_request_idx` (`ai_chat_conversation_id`, `request_id`, `tool_name`, `arguments_digest`),
    KEY                                `ai_chat_mutation_confirmation_expiry_idx` (`status`, `expires_at`),
    CONSTRAINT `ai_chat_mutation_confirmation_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='One-time server-authoritative grants for AI mutation tool calls.';
