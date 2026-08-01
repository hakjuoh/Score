CREATE TABLE `ai_provider`
(
    `ai_provider_id`     bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the AI provider',
    `provider_name`      varchar(120) NOT NULL COMMENT 'Unique application-facing name of the AI provider',
    `provider_type`      varchar(32) NOT NULL COMMENT 'Provider adapter type: anthropic or openai',
    `base_url`           varchar(1000) NULL COMMENT 'Base endpoint URL of the AI provider',
    `messages_url`       varchar(1000) NULL COMMENT 'Optional provider-specific messages endpoint URL',
    `api_version`        varchar(64) NULL COMMENT 'Provider API version sent using the provider-specific mechanism',
    `api_key_secret_id`  bigint unsigned NULL COMMENT 'Encrypted API key referenced from app_secret',
    `enabled`            tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Indicates whether the provider can serve model requests',
    `created_by`         bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who created the provider',
    `last_updated_by`    bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who last updated the provider',
    `created_at`         datetime(6) NOT NULL COMMENT 'Date and time when the provider was created',
    `last_updated_at`    datetime(6) NOT NULL COMMENT 'Date and time when the provider was last updated',
    PRIMARY KEY (`ai_provider_id`),
    UNIQUE KEY `ai_provider_name_uk` (`provider_name`),
    UNIQUE KEY `ai_provider_api_key_secret_uk` (`api_key_secret_id`),
    CONSTRAINT `ai_provider_type_ck` CHECK (`provider_type` IN ('anthropic', 'openai')),
    CONSTRAINT `ai_provider_api_key_secret_fk`
        FOREIGN KEY (`api_key_secret_id`) REFERENCES `app_secret` (`app_secret_id`) ON DELETE RESTRICT,
    CONSTRAINT `ai_provider_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL,
    CONSTRAINT `ai_provider_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Configured AI providers and encrypted API key references';
