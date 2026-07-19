-- ----------------------------------------------------
-- Migration script for Score v3.6.0                 --
--                                                   --
-- Author: Hakju Oh <hakju.oh@nist.gov>              --
-- ----------------------------------------------------

SET @OLD_FOREIGN_KEY_CHECKS = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------------
-- Passkey (WebAuthn) sign-in
-- ------------------------------------------------------------------
--
-- Passkeys let a local account sign in with a fingerprint, face or screen lock instead of a password.
--
-- `app_webauthn_user` is the WebAuthn identity (user handle) of an `app_user`; `webauthn_credential` holds the
-- passkeys, many per user. The user handle is random and opaque, never derived from the login ID or email.
--
-- These tables may already exist on a database that ran a development build, with rows in them. They are dropped
-- and created again, so any passkey registered by such a build is lost and has to be registered again. The
-- credential table goes first because it refers to the user table.
DROP TABLE IF EXISTS `webauthn_credential`;
DROP TABLE IF EXISTS `app_webauthn_user`;

CREATE TABLE `app_webauthn_user`
(
    `app_webauthn_user_id`  bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'Primary key.',
    `app_user_id`           bigint(20) unsigned NOT NULL COMMENT 'A reference to the record in `app_user`.',
    `user_handle`           varbinary(64)       NOT NULL COMMENT 'Opaque random WebAuthn user handle (max 64 bytes per the spec). It is the only user identifier the authenticator stores, so it must not be derived from login_id or email.',
    `creation_timestamp`    datetime(6)         NOT NULL COMMENT 'Timestamp when this record is created.',
    `last_update_timestamp` datetime(6)         NOT NULL COMMENT 'Timestamp when this record is last updated.',
    PRIMARY KEY (`app_webauthn_user_id`),
    UNIQUE KEY `app_webauthn_user_uk1` (`user_handle`),
    KEY `app_webauthn_user_app_user_id_fk` (`app_user_id`),
    CONSTRAINT `app_webauthn_user_app_user_id_fk` FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='WebAuthn identity (user handle) of an app_user; created on the first passkey registration.';

CREATE TABLE `webauthn_credential`
(
    `webauthn_credential_id`       bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'Primary key.',
    `app_webauthn_user_id`         bigint(20) unsigned NOT NULL COMMENT 'A reference to the record in `app_webauthn_user`; the owner of the passkey.',
    `credential_id`                varbinary(1023)     NOT NULL COMMENT 'The authenticator-assigned credential ID (max 1023 bytes per the spec). Globally unique.',
    `rp_id`                        varchar(255)        NOT NULL COMMENT 'The relying party ID (domain) this passkey was registered for. A passkey only works while the configured RP ID matches; used to find and clean up passkeys after a domain change.',
    `public_key`                   blob                NOT NULL COMMENT 'The credential public key (COSE-encoded).',
    `signature_count`              bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT 'Last seen authenticator signature counter, for information only. Sign-in compares the counter in attestation_object, not this value, so cloned authenticators are not detected. Synced passkeys commonly keep it at 0.',
    `uv_initialized`               tinyint(1)          NOT NULL DEFAULT 0 COMMENT 'Whether user verification (PIN/biometric) was performed at registration.',
    `backup_eligible`              tinyint(1)          NOT NULL DEFAULT 0 COMMENT 'WebAuthn BE flag: the credential may be synced/backed up (a multi-device passkey).',
    `backup_state`                 tinyint(1)          NOT NULL DEFAULT 0 COMMENT 'WebAuthn BS flag: the credential is currently backed up.',
    `transports`                   varchar(255)                 DEFAULT NULL COMMENT 'Comma-separated authenticator transports (usb, nfc, ble, hybrid, internal), used as a sign-in hint.',
    `credential_type`              varchar(32)         NOT NULL DEFAULT 'public-key' COMMENT 'The PublicKeyCredential type.',
    `attestation_object`           blob                         DEFAULT NULL COMMENT 'The attestation object returned at registration. Required for sign-in: the credential public key and the previous counter are read from it when an assertion is verified.',
    `attestation_client_data_json` blob                         DEFAULT NULL COMMENT 'The clientDataJSON returned at registration, kept for auditing.',
    `label`                        varchar(100)                 DEFAULT NULL COMMENT 'User-given name of the passkey, e.g. "MacBook Touch ID".',
    `last_used_timestamp`          datetime(6)                  DEFAULT NULL COMMENT 'Timestamp when this passkey was last used to sign in; NULL until it is used.',
    `last_used_user_agent`         varchar(512)                 DEFAULT NULL COMMENT 'The User-Agent header of the request that last used this passkey.',
    `creation_timestamp`           datetime(6)         NOT NULL COMMENT 'Timestamp when this passkey is registered.',
    `last_update_timestamp`        datetime(6)         NOT NULL COMMENT 'Timestamp when this record is last updated (rename, or authenticator data refreshed after a sign-in).',
    PRIMARY KEY (`webauthn_credential_id`),
    UNIQUE KEY `webauthn_credential_uk1` (`credential_id`),
    KEY `webauthn_credential_app_webauthn_user_id_fk` (`app_webauthn_user_id`),
    CONSTRAINT `webauthn_credential_app_webauthn_user_id_fk` FOREIGN KEY (`app_webauthn_user_id`) REFERENCES `app_webauthn_user` (`app_webauthn_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='Registered passkeys (WebAuthn credentials) of local accounts.';

SET FOREIGN_KEY_CHECKS = @OLD_FOREIGN_KEY_CHECKS;

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
    `message_type`            varchar(16) NOT NULL COMMENT 'The message role in the model context: USER, ASSISTANT, SYSTEM, or TOOL.',
    `content`                 longtext    NOT NULL COMMENT 'The text content supplied to the model as conversation memory.',
    `metadata_json`           JSON NULL COMMENT 'Optional JSON metadata associated with the model-context message.',
    `created_at`              datetime(6) NOT NULL COMMENT 'The timestamp when the memory record was created.',
    PRIMARY KEY (`ai_chat_memory_id`),
    UNIQUE KEY `ai_chat_memory_conversation_sequence_uk` (`ai_chat_conversation_id`, `memory_sequence`),
    CONSTRAINT `ai_chat_memory_conversation_fk`
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_chat_memory_type_ck`
        CHECK (`message_type` IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
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
    `source`                  varchar(16) NOT NULL COMMENT 'The ATIF source of the step: system, user, or agent.',
    `message_kind`            varchar(32) NOT NULL COMMENT 'The application-specific kind of trajectory event represented by the step.',
    `visibility`              varchar(16) NOT NULL DEFAULT 'visible' COMMENT 'The presentation scope of the step: visible or debug.',
    `message`                 longtext    NOT NULL COMMENT 'The textual message or event content recorded for the step.',
    `reasoning_content`       longtext NULL COMMENT 'Optional model reasoning content associated with the step.',
    `model_name`              varchar(240) NULL COMMENT 'The model used for the inference represented by the step.',
    `reasoning_effort`        varchar(32) NULL COMMENT 'The reasoning-effort setting used for the model inference.',
    `agent_runtime`           varchar(32) NULL COMMENT 'The agent runtime selected for the conversation at this step.',
    `runtime_options_json`    JSON NULL COMMENT 'JSON object containing runtime-specific options used for the step.',
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
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_chat_step_source_ck` CHECK (`source` IN ('system', 'user', 'agent')),
    CONSTRAINT `ai_chat_step_visibility_ck` CHECK (`visibility` IN ('visible', 'debug')),
    CONSTRAINT `ai_chat_step_settings_change_ck` CHECK (
        `message_kind` <> 'settings_change'
            OR (`model_name` IS NOT NULL AND `reasoning_effort` IS NOT NULL AND `agent_runtime` IS NOT NULL)
        )
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
    `status`                           varchar(16)                      NOT NULL COMMENT 'The confirmation lifecycle status: REQUESTED, APPROVED, DENIED, CONSUMED, or EXPIRED.',
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
        FOREIGN KEY (`ai_chat_conversation_id`) REFERENCES `ai_chat_conversation` (`ai_chat_conversation_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_chat_mutation_confirmation_status_ck`
        CHECK (`status` IN ('REQUESTED', 'APPROVED', 'DENIED', 'CONSUMED', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
  ROW_FORMAT = DYNAMIC COMMENT ='One-time server-authoritative grants for AI mutation tool calls.';
