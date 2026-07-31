CREATE TABLE `ai_token_usage_period`
(
    `app_user_id`     bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user whose token usage is aggregated',
    `period_start`    datetime(6) NOT NULL COMMENT 'Inclusive start of the quota period in UTC',
    `period_end`      datetime(6) NOT NULL COMMENT 'Exclusive end of the quota period in UTC',
    `consumed_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens consumed during the period',
    `reserved_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens reserved by in-progress calls during the period',
    `updated_at`      datetime(6) NOT NULL COMMENT 'Date and time when the period counter was last updated',
    PRIMARY KEY (`app_user_id`, `period_start`, `period_end`),
    KEY `ai_token_usage_period_end_idx` (`period_end`),
    CONSTRAINT `ai_token_usage_period_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Per-user token counter for a quota period';
