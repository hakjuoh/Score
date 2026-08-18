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
    `creation_timestamp`             datetime(6) NOT NULL COMMENT 'The timestamp when the conversation was created.',
    `last_update_timestamp`          datetime(6) NOT NULL COMMENT 'The timestamp of the most recent activity in the conversation.',
    PRIMARY KEY (`ai_chat_conversation_id`),
    UNIQUE KEY `ai_chat_conversation_guid_uk` (`guid`),
    KEY                              `ai_chat_conversation_owner_updated_idx` (`app_user_id`, `last_update_timestamp`),
    KEY                              `ai_chat_conversation_parent_idx` (`parent_ai_chat_conversation_id`, `creation_timestamp`),
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
    `creation_timestamp`      datetime(6) NOT NULL COMMENT 'The timestamp when the memory record was created.',
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
    `creation_timestamp`      datetime(6) NOT NULL COMMENT 'The timestamp when the trajectory step was created.',
    PRIMARY KEY (`ai_chat_step_id`),
    UNIQUE KEY `ai_chat_step_conversation_sequence_uk` (`ai_chat_conversation_id`, `step_sequence`),
    KEY                       `ai_chat_step_request_idx` (`ai_chat_conversation_id`, `request_id`),
    KEY                       `ai_chat_step_settings_idx` (`ai_chat_conversation_id`, `message_kind`, `step_sequence`),
    KEY                       `ai_chat_step_created_idx` (`ai_chat_conversation_id`, `creation_timestamp`),
    CONSTRAINT `ai_chat_step_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Complete connectCenter assistant trajectory in ATIF-reconstructable steps.';

CREATE TABLE `ai_chat_change_confirmation`
(
    `ai_chat_change_confirmation_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the AI change confirmation record.',
    `guid`                             char(36) COLLATE utf8mb4_bin     NOT NULL COMMENT 'The public unique identifier of the change confirmation request.',
    `ai_chat_conversation_id`          bigint(20) unsigned NOT NULL COMMENT 'Foreign key to the AI_CHAT_CONVERSATION table identifying the owning conversation.',
    `request_id`                       varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'The chat request identifier that originally requested the data-changing tool call.',
    `tool_name`                        varchar(240)                     NOT NULL COMMENT 'The name of the data-changing tool that requires explicit user approval.',
    `arguments_digest`                 char(64) COLLATE ascii_bin       NOT NULL COMMENT 'The SHA-256 digest binding the tool name to its canonicalized arguments.',
    `status`                           varchar(16)                      NULL DEFAULT 'REQUESTED' COMMENT 'Expected confirmation states are REQUESTED, APPROVED, DENIED, CONSUMED, and EXPIRED; other values are handled by the application.',
    `grant_digest`                     char(64) COLLATE ascii_bin NULL COMMENT 'The SHA-256 digest of the one-time approval grant; cleared after consumption, denial, or expiration.',
    `expiration_timestamp`             datetime(6) NOT NULL COMMENT 'The timestamp after which the confirmation request or approval grant is invalid.',
    `approved_timestamp`               datetime(6) NULL COMMENT 'The timestamp when the change request was approved.',
    `denied_timestamp`                 datetime(6) NULL COMMENT 'The timestamp when the change request was denied.',
    `consumed_timestamp`               datetime(6) NULL COMMENT 'The timestamp when the one-time approval grant was consumed.',
    `expired_timestamp`                datetime(6) NULL COMMENT 'The timestamp when the confirmation request or approval grant expired.',
    `creation_timestamp`               datetime(6) NOT NULL COMMENT 'The timestamp when the change confirmation request was created.',
    PRIMARY KEY (`ai_chat_change_confirmation_id`),
    UNIQUE KEY `ai_chat_change_confirmation_guid_uk` (`guid`),
    UNIQUE KEY `ai_chat_change_confirmation_grant_uk` (`grant_digest`),
    KEY                                `ai_chat_change_confirmation_request_idx` (`ai_chat_conversation_id`, `request_id`, `tool_name`, `arguments_digest`),
    KEY                                `ai_chat_change_confirmation_expiry_idx` (`status`, `expiration_timestamp`),
    CONSTRAINT `ai_chat_change_confirmation_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='One-time server-authoritative grants for AI change tool calls.';

CREATE TABLE `ai_chat_file`
(
    `ai_chat_file_id`     bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'The primary key of the generated AI file.',
    `guid`                    char(36) COLLATE utf8mb4_bin NOT NULL COMMENT 'Public file identifier.',
    `ai_chat_conversation_id` bigint(20) unsigned NOT NULL COMMENT 'Owning AI chat conversation.',
    `request_id`              varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Request that created the file.',
    `format`                  varchar(64) NOT NULL COMMENT 'Renderer format identifier.',
    `filename`                varchar(240) NOT NULL COMMENT 'Safe download filename.',
    `media_type`              varchar(160) NOT NULL COMMENT 'File media type.',
    `byte_size`               bigint unsigned NOT NULL COMMENT 'Stored content size in bytes.',
    `sha256`                  char(64) COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 digest of stored content.',
    `storage_provider`        varchar(64) NOT NULL COMMENT 'Configured file storage provider identifier.',
    `storage_location`        varchar(1024) COLLATE utf8mb4_bin NOT NULL COMMENT 'Provider-owned opaque object location.',
    `creation_timestamp`      datetime(6) NOT NULL COMMENT 'File creation timestamp.',
    `expiration_timestamp`    datetime(6) NOT NULL COMMENT 'File retention deadline.',
    PRIMARY KEY (`ai_chat_file_id`),
    UNIQUE KEY `ai_chat_file_guid_uk` (`guid`),
    UNIQUE KEY `ai_chat_file_request_digest_uk`
        (`ai_chat_conversation_id`, `request_id`, `filename`, `sha256`),
    KEY `ai_chat_file_request_idx` (`ai_chat_conversation_id`, `request_id`, `creation_timestamp`),
    KEY `ai_chat_file_expiry_idx` (`expiration_timestamp`),
    CONSTRAINT `ai_chat_file_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Generated Assistant file metadata and provider location history.';

CREATE TABLE `ai_chat_file_object`
(
    `storage_location` varchar(512) COLLATE utf8mb4_bin NOT NULL COMMENT 'Opaque database-storage object key.',
    `content`          longblob NOT NULL COMMENT 'File bytes for the database storage provider.',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Object creation timestamp.',
    PRIMARY KEY (`storage_location`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Binary objects used only when score.ai.tools.files.storage.provider is db.';

-- ----------------------------------------------------
-- AI provider, model, policy, and token quota tables --
-- ----------------------------------------------------

CREATE TABLE `app_secret`
(
    `app_secret_id`      bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the encrypted application secret',
    `secret_guid`        char(36) COLLATE ascii_bin NOT NULL COMMENT 'Stable UUID used as authenticated encryption context',
    `secret_name`        varchar(255) NOT NULL COMMENT 'Unique logical name of the application secret',
    `secret_type`        varchar(32) NOT NULL COMMENT 'Secret type such as AI_PROVIDER_API_KEY',
    `encrypted_value`    mediumblob NOT NULL COMMENT 'AES-256-GCM ciphertext including the authentication tag',
    `nonce`              binary(12) NOT NULL COMMENT 'Unique 96-bit AES-GCM nonce for this encrypted value',
    `encryption_key_id`  varchar(64) NOT NULL COMMENT 'Identifier of the application encryption key used for this value',
    `encryption_version` smallint unsigned NOT NULL DEFAULT 1 COMMENT 'Version of the encryption payload format',
    `created_by`         bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who created the secret',
    `last_updated_by`    bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who last updated the secret',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the secret was created',
    `last_update_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the secret was last updated',
    PRIMARY KEY (`app_secret_id`),
    UNIQUE KEY `app_secret_guid_uk` (`secret_guid`),
    UNIQUE KEY `app_secret_name_uk` (`secret_name`),
    CONSTRAINT `app_secret_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `app_secret_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Encrypted application secrets; initially limited to AI provider API keys';

CREATE TABLE `ai_provider`
(
    `ai_provider_id`     bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the AI provider',
    `provider_name`      varchar(120) NOT NULL COMMENT 'Unique application-facing name of the AI provider',
    `provider_type`      varchar(32) NOT NULL COMMENT 'Provider adapter type: anthropic or openai',
    `base_url`           varchar(1000) NULL COMMENT 'Base endpoint URL of the AI provider',
    `messages_url`       varchar(1000) NULL COMMENT 'Optional provider-specific messages endpoint URL',
    `api_version`        varchar(64) NULL COMMENT 'Provider API version sent using the provider-specific mechanism',
    `api_key_secret_id`  bigint unsigned NULL COMMENT 'Encrypted API key referenced from app_secret',
    `enabled`            tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the provider can serve model requests',
    `created_by`         bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who created the provider',
    `last_updated_by`    bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who last updated the provider',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the provider was created',
    `last_update_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the provider was last updated',
    PRIMARY KEY (`ai_provider_id`),
    UNIQUE KEY `ai_provider_name_uk` (`provider_name`),
    UNIQUE KEY `ai_provider_api_key_secret_uk` (`api_key_secret_id`),
    CONSTRAINT `ai_provider_type_ck` CHECK (`provider_type` IN ('anthropic', 'openai')),
    CONSTRAINT `ai_provider_api_key_secret_fk`
        FOREIGN KEY (`api_key_secret_id`) REFERENCES `app_secret` (`app_secret_id`) ON DELETE RESTRICT,
    CONSTRAINT `ai_provider_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `ai_provider_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Configured AI providers and encrypted API key references';

CREATE TABLE `ai_model`
(
    `ai_model_id`                  bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the configured AI model',
    `provider_id`                  bigint unsigned NOT NULL COMMENT 'Identifier of the provider that serves the model',
    `model_key`                    varchar(240) NOT NULL COMMENT 'Stable application-facing model key',
    `provider_model_name`          varchar(240) NOT NULL COMMENT 'Model or deployment name sent to the provider',
    `display_name`                 varchar(240) NOT NULL COMMENT 'Display name shown to administrators and users',
    `description`                  varchar(1000) NOT NULL COMMENT 'Human-readable description of the model',
    `enabled`                      tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the model is available for new requests',
    `default_model`                tinyint(1) NULL DEFAULT NULL COMMENT 'Set to 1 only for the global default AI model',
    `lightweight_model`            tinyint(1) NULL DEFAULT NULL COMMENT 'Set to 1 only for the global lightweight AI model',
    `sort_order`                   int unsigned NOT NULL DEFAULT 0 COMMENT 'Stable display and fallback ordering of AI models',
    `max_tokens`                   int unsigned NULL COMMENT 'Maximum output-token budget for one provider call',
    `context_window`               bigint unsigned NOT NULL COMMENT 'Maximum context window size in tokens',
    `output_reserve_tokens`        bigint unsigned NULL COMMENT 'Output tokens reserved during context budget calculation',
    `auto_compact_threshold_tokens` bigint unsigned NULL COMMENT 'Context token threshold that triggers automatic compaction',
    `emergency_headroom_tokens`    bigint unsigned NOT NULL DEFAULT 4096 COMMENT 'Emergency context headroom in tokens',
    `tool_output_token_limit`      bigint unsigned NOT NULL DEFAULT 32000 COMMENT 'Maximum tool output tokens retained in context',
    `model_options_json`           JSON NULL COMMENT 'Configured Spring AI model options as a JSON object',
    `created_by`                   bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who created the model',
    `last_updated_by`              bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who last updated the model',
    `creation_timestamp`           datetime(6) NOT NULL COMMENT 'Date and time when the model was created',
    `last_update_timestamp`        datetime(6) NOT NULL COMMENT 'Date and time when the model was last updated',
    PRIMARY KEY (`ai_model_id`),
    UNIQUE KEY `ai_model_key_uk` (`model_key`),
    UNIQUE KEY `ai_model_default_model_uk` (`default_model`),
    UNIQUE KEY `ai_model_lightweight_model_uk` (`lightweight_model`),
    KEY `ai_model_provider_idx` (`provider_id`),
    CONSTRAINT `ai_model_default_model_ck` CHECK (`default_model` = 1 OR `default_model` IS NULL),
    CONSTRAINT `ai_model_lightweight_model_ck` CHECK (`lightweight_model` = 1 OR `lightweight_model` IS NULL),
    CONSTRAINT `ai_model_provider_fk`
        FOREIGN KEY (`provider_id`) REFERENCES `ai_provider` (`ai_provider_id`) ON DELETE RESTRICT,
    CONSTRAINT `ai_model_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `ai_model_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Configured AI models and provider-specific runtime options';

CREATE TABLE `ai_model_reasoning_effort`
(
    `ai_model_id`     bigint unsigned NOT NULL COMMENT 'Identifier of the configured AI model',
    `reasoning_effort` varchar(32) NOT NULL COMMENT 'Canonical reasoning effort name',
    `display_name`    varchar(120) NOT NULL COMMENT 'Display name of the reasoning effort',
    `description`     varchar(500) NOT NULL COMMENT 'Human-readable description of the reasoning effort',
    `default_effort`  tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Indicates whether this is the model default reasoning effort',
    `sort_order`      int unsigned NOT NULL DEFAULT 0 COMMENT 'Stable display ordering of reasoning efforts',
    PRIMARY KEY (`ai_model_id`, `reasoning_effort`),
    CONSTRAINT `ai_model_reasoning_effort_model_fk`
        FOREIGN KEY (`ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Reasoning efforts supported by each AI model';

CREATE TABLE `ai_user_policy`
(
    `app_user_id`                  bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user to whom the policy applies',
    `ai_enabled`                   tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the user can use AI Assistant',
    `model_access_mode`            varchar(16) NOT NULL DEFAULT 'ALL' COMMENT 'Model access mode: ALL or ALLOW_LIST',
    `default_ai_model_id`          bigint unsigned NULL COMMENT 'Identifier of the default AI model for the user',
    `multi_agent_enabled`          tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the user can use multi-agent workflows',
    `max_agents_per_request`       tinyint unsigned NOT NULL DEFAULT 4 COMMENT 'Maximum number of agents allowed per request',
    `max_active_requests`          tinyint unsigned NOT NULL DEFAULT 8 COMMENT 'Maximum number of concurrent AI requests for the user',
    `max_output_tokens_per_call`   bigint unsigned NULL COMMENT 'Maximum number of output tokens allowed per model call',
    `max_total_tokens_per_request` bigint unsigned NULL COMMENT 'Maximum total tokens allowed for a root request',
    `quota_period`                 varchar(16) NULL COMMENT 'Cumulative token quota period: DAILY or MONTHLY',
    `quota_tokens`                 bigint unsigned NULL COMMENT 'Maximum cumulative tokens allowed during the quota period',
    `policy_version`               bigint unsigned NOT NULL DEFAULT 1 COMMENT 'Policy version used for optimistic locking',
    `created_by`                   bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who created the policy',
    `last_updated_by`              bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who last updated the policy',
    `creation_timestamp`           datetime(6) NOT NULL COMMENT 'Date and time when the policy was created',
    `last_update_timestamp`        datetime(6) NOT NULL COMMENT 'Date and time when the policy was last updated',
    PRIMARY KEY (`app_user_id`),
    CONSTRAINT `ai_user_policy_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_user_policy_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `ai_user_policy_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `ai_user_policy_default_model_fk`
        FOREIGN KEY (`default_ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user AI Assistant access and usage limit policy';

CREATE TABLE `ai_user_model_access`
(
    `app_user_id` bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user to whom the policy applies',
    `ai_model_id` bigint unsigned NOT NULL COMMENT 'Identifier of an AI model allowed for the user',
    PRIMARY KEY (`app_user_id`, `ai_model_id`),
    CONSTRAINT `ai_user_model_access_policy_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `ai_user_policy` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_user_model_access_model_fk`
        FOREIGN KEY (`ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user AI model allowlist';

CREATE TABLE `ai_user_model_reasoning_access`
(
    `app_user_id`      bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user to whom the policy applies',
    `ai_model_id`      bigint unsigned NOT NULL COMMENT 'Identifier of the AI model subject to the reasoning effort restriction',
    `reasoning_effort` varchar(32) NOT NULL COMMENT 'Name of a reasoning effort allowed for the user',
    PRIMARY KEY (`app_user_id`, `ai_model_id`, `reasoning_effort`),
    CONSTRAINT `ai_user_model_reasoning_access_policy_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `ai_user_policy` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_user_model_reasoning_access_effort_fk`
        FOREIGN KEY (`ai_model_id`, `reasoning_effort`)
        REFERENCES `ai_model_reasoning_effort` (`ai_model_id`, `reasoning_effort`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user and per-model reasoning effort allowlist';

CREATE TABLE `ai_token_usage_period`
(
    `app_user_id`     bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user whose token usage is aggregated',
    `period_start_timestamp` datetime(6) NOT NULL COMMENT 'Inclusive start of the quota period in UTC',
    `period_end_timestamp` datetime(6) NOT NULL COMMENT 'Exclusive end of the quota period in UTC',
    `consumed_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens consumed during the period',
    `reserved_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens reserved by in-progress calls during the period',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the period counter was created',
    `last_update_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the period counter was last updated',
    PRIMARY KEY (`app_user_id`, `period_start_timestamp`, `period_end_timestamp`),
    KEY `ai_token_usage_period_end_idx` (`period_end_timestamp`),
    CONSTRAINT `ai_token_usage_period_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user token counter for a quota period';

CREATE TABLE `ai_token_request_usage`
(
    `request_id`      varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Identifier of the root AI request',
    `app_user_id`     bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user who submitted the AI request',
    `consumed_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens consumed by the request',
    `reserved_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens reserved by in-progress calls for the request',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the request usage counter was created',
    `last_update_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the request usage counter was last updated',
    PRIMARY KEY (`request_id`),
    KEY `ai_token_request_usage_user_created_idx` (`app_user_id`, `creation_timestamp`),
    CONSTRAINT `ai_token_request_usage_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Token counter for each root AI request';

CREATE TABLE `ai_token_usage_ledger`
(
    `ai_token_usage_ledger_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the token usage ledger entry',
    `call_id`                   char(36) COLLATE ascii_bin NOT NULL COMMENT 'UUID of the provider call attempt',
    `request_id`                varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Identifier of the root AI request',
    `conversation_guid`         char(36) COLLATE utf8mb4_bin NULL COMMENT 'GUID of the related AI conversation',
    `app_user_id`               bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user who initiated the AI call',
    `ai_model_id`               bigint unsigned NOT NULL COMMENT 'Identifier of the AI model that was called',
    `execution_kind`            varchar(64) NULL COMMENT 'Execution kind such as assistant, planner, or worker',
    `agent_id`                  varchar(64) NULL COMMENT 'Identifier of the agent that performed the call',
    `reserved_tokens`           bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens reserved before the provider call',
    `quota_period_start_timestamp` datetime(6) NULL COMMENT 'Exact inclusive quota window start reserved by this call',
    `quota_period_end_timestamp` datetime(6) NULL COMMENT 'Exact exclusive quota window end reserved by this call',
    `prompt_tokens`             bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Normalized input tokens reported by the provider',
    `completion_tokens`         bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Output tokens reported by the provider',
    `cached_tokens`             bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of input tokens served from cache',
    `charged_tokens`            bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens charged against the quota',
    `usage_complete`            tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Indicates whether the provider usage data is complete',
    `status`                    varchar(16) NOT NULL COMMENT 'Reservation and settlement status',
    `failure_type`              varchar(240) NULL COMMENT 'Failure class or error code',
    `reserved_timestamp`        datetime(6) NOT NULL COMMENT 'Date and time when the tokens were reserved',
    `settled_timestamp`         datetime(6) NULL COMMENT 'Date and time when the usage was settled or released',
    PRIMARY KEY (`ai_token_usage_ledger_id`),
    UNIQUE KEY `ai_token_usage_ledger_call_uk` (`call_id`),
    KEY `ai_token_usage_ledger_user_time_idx` (`app_user_id`, `reserved_timestamp`),
    KEY `ai_token_usage_ledger_request_idx` (`request_id`, `reserved_timestamp`),
    KEY `ai_token_usage_ledger_stale_reservation_idx` (`status`, `reserved_timestamp`),
    CONSTRAINT `ai_token_usage_ledger_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_token_usage_ledger_model_fk`
        FOREIGN KEY (`ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Token reservation and usage ledger for each provider call attempt';

CREATE TABLE `ai_token_quota_adjustment`
(
    `ai_token_quota_adjustment_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the token quota adjustment',
    `target_app_user_id`           bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user whose quota was adjusted',
    `actor_app_user_id`            bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who adjusted the quota',
    `period_start_timestamp`       datetime(6) NOT NULL COMMENT 'Start of the adjusted quota period in UTC',
    `delta_tokens`                 bigint NOT NULL COMMENT 'Positive or negative token amount applied to consumed usage',
    `reason`                       varchar(500) NOT NULL COMMENT 'Reason for the quota adjustment',
    `creation_timestamp`           datetime(6) NOT NULL COMMENT 'Date and time when the quota was adjusted',
    PRIMARY KEY (`ai_token_quota_adjustment_id`),
    KEY `ai_token_quota_adjustment_target_time_idx`
        (`target_app_user_id`, `creation_timestamp`),
    CONSTRAINT `ai_token_quota_adjustment_actor_fk`
        FOREIGN KEY (`actor_app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='History of manual user quota adjustments by administrators';
