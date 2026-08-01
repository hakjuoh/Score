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
