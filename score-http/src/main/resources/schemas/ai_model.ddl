CREATE TABLE `ai_model`
(
    `ai_model_id`                  bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the AI model catalog entry',
    `provider_id`                  bigint unsigned NOT NULL COMMENT 'Identifier of the provider that serves the model',
    `model_key`                    varchar(240) NOT NULL COMMENT 'Stable application-facing model key',
    `provider_model_name`          varchar(240) NOT NULL COMMENT 'Model or deployment name sent to the provider',
    `display_name`                 varchar(240) NOT NULL COMMENT 'Display name shown to administrators and users',
    `description`                  varchar(1000) NOT NULL COMMENT 'Human-readable description of the model',
    `enabled`                      tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the model is available for new requests',
    `sort_order`                   int unsigned NOT NULL DEFAULT 0 COMMENT 'Stable display and fallback ordering of catalog models',
    `max_tokens`                   int unsigned NULL COMMENT 'Maximum output-token budget for one provider call',
    `context_window`               bigint unsigned NOT NULL COMMENT 'Maximum context window size in tokens',
    `output_reserve_tokens`        bigint unsigned NULL COMMENT 'Output tokens reserved during context budget calculation',
    `auto_compact_threshold_tokens` bigint unsigned NULL COMMENT 'Context token threshold that triggers automatic compaction',
    `emergency_headroom_tokens`    bigint unsigned NOT NULL DEFAULT 4096 COMMENT 'Emergency context headroom in tokens',
    `tool_output_token_limit`      bigint unsigned NOT NULL DEFAULT 32000 COMMENT 'Maximum tool output tokens retained in context',
    `model_options_json`           JSON NULL COMMENT 'Configured Spring AI model options as a JSON object',
    `catalog_version`              bigint unsigned NOT NULL DEFAULT 1 COMMENT 'Optimistic locking and cache invalidation version',
    `created_by`                   bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who created the model',
    `last_updated_by`              bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who last updated the model',
    `created_at`                   datetime(6) NOT NULL COMMENT 'Date and time when the model was created',
    `last_updated_at`              datetime(6) NOT NULL COMMENT 'Date and time when the model was last updated',
    PRIMARY KEY (`ai_model_id`),
    UNIQUE KEY `ai_model_key_uk` (`model_key`),
    KEY `ai_model_provider_idx` (`provider_id`),
    CONSTRAINT `ai_model_provider_fk`
        FOREIGN KEY (`provider_id`) REFERENCES `ai_provider` (`ai_provider_id`) ON DELETE RESTRICT,
    CONSTRAINT `ai_model_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL,
    CONSTRAINT `ai_model_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Database-backed AI model catalog';
