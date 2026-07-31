CREATE TABLE `ai_token_request_usage`
(
    `request_id`      varchar(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Identifier of the root AI request',
    `app_user_id`     bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user who submitted the AI request',
    `consumed_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens consumed by the request',
    `reserved_tokens` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Number of tokens reserved by in-progress calls for the request',
    `created_at`      datetime(6) NOT NULL COMMENT 'Date and time when the request usage counter was created',
    `updated_at`      datetime(6) NOT NULL COMMENT 'Date and time when the request usage counter was last updated',
    PRIMARY KEY (`request_id`),
    KEY `ai_token_request_usage_user_created_idx` (`app_user_id`, `created_at`),
    CONSTRAINT `ai_token_request_usage_user_fk`
        FOREIGN KEY (`app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Token counter for each root AI request';
