CREATE TABLE `ai_model_catalog_config`
(
    `ai_model_catalog_config_id` tinyint unsigned NOT NULL COMMENT 'Singleton AI model catalog configuration identifier',
    `default_ai_model_id`        bigint unsigned NOT NULL COMMENT 'Identifier of the global default AI model',
    `catalog_version`            bigint unsigned NOT NULL DEFAULT 1 COMMENT 'Optimistic locking and cache invalidation version',
    `last_updated_by`            bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who last updated the catalog configuration',
    `last_updated_at`            datetime(6) NOT NULL COMMENT 'Date and time when the catalog configuration was last updated',
    PRIMARY KEY (`ai_model_catalog_config_id`),
    CONSTRAINT `ai_model_catalog_config_default_model_fk`
        FOREIGN KEY (`default_ai_model_id`) REFERENCES `ai_model` (`ai_model_id`) ON DELETE RESTRICT,
    CONSTRAINT `ai_model_catalog_config_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Singleton global AI model catalog configuration';
