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
