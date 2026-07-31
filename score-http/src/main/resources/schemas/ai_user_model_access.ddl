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
