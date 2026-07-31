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
