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
    `created_by`                   bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who created the policy',
    `last_updated_by`              bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who last updated the policy',
    `created_at`                   datetime(6) NOT NULL COMMENT 'Date and time when the policy was created',
    `last_updated_at`              datetime(6) NOT NULL COMMENT 'Date and time when the policy was last updated',
    PRIMARY KEY (`app_user_id`),
    CONSTRAINT `ai_user_policy_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE,
    CONSTRAINT `ai_user_policy_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL,
    CONSTRAINT `ai_user_policy_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL,
    CONSTRAINT `ai_user_policy_default_model_fk`
        FOREIGN KEY (`default_ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user AI Assistant access and usage limit policy';
