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
    `quota_period_start`        datetime(6) NULL COMMENT 'Exact inclusive quota window start reserved by this call',
    `quota_period_end`          datetime(6) NULL COMMENT 'Exact exclusive quota window end reserved by this call',
    `prompt_tokens`             bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Normalized input tokens reported by the provider',
    `completion_tokens`         bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Output tokens reported by the provider',
    `cached_tokens`             bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of input tokens served from cache',
    `charged_tokens`            bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens charged against the quota',
    `usage_complete`            tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Indicates whether the provider usage data is complete',
    `status`                    varchar(16) NOT NULL COMMENT 'Reservation and settlement status',
    `failure_type`              varchar(240) NULL COMMENT 'Failure class or error code',
    `reserved_at`               datetime(6) NOT NULL COMMENT 'Date and time when the tokens were reserved',
    `settled_at`                datetime(6) NULL COMMENT 'Date and time when the usage was settled or released',
    PRIMARY KEY (`ai_token_usage_ledger_id`),
    UNIQUE KEY `ai_token_usage_ledger_call_uk` (`call_id`),
    KEY `ai_token_usage_ledger_user_time_idx` (`app_user_id`, `reserved_at`),
    KEY `ai_token_usage_ledger_stale_reservation_idx` (`status`, `reserved_at`),
    KEY `ai_token_usage_ledger_request_idx` (`request_id`, `reserved_at`),
    CONSTRAINT `ai_token_usage_ledger_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_token_usage_ledger_model_fk`
        FOREIGN KEY (`ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Token reservation and usage ledger for each provider call attempt';
