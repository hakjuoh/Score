CREATE TABLE `ai_catalog_audit`
(
    `ai_catalog_audit_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the AI catalog audit entry',
    `entity_type`         varchar(32) NOT NULL COMMENT 'Catalog entity type such as PROVIDER or MODEL',
    `entity_id`           bigint unsigned NOT NULL COMMENT 'Identifier of the changed catalog entity',
    `actor_app_user_id`   bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who changed the catalog',
    `action`              varchar(16) NOT NULL COMMENT 'Catalog change action such as CREATE, UPDATE, DISABLE, or ROTATE_KEY',
    `before_json`         JSON NULL COMMENT 'Non-secret catalog snapshot before the change',
    `after_json`          JSON NULL COMMENT 'Non-secret catalog snapshot after the change',
    `reason`              varchar(500) NULL COMMENT 'Reason or administrator comment for the catalog change',
    `created_at`          datetime(6) NOT NULL COMMENT 'Date and time when the catalog was changed',
    PRIMARY KEY (`ai_catalog_audit_id`),
    KEY `ai_catalog_audit_entity_time_idx` (`entity_type`, `entity_id`, `created_at`),
    CONSTRAINT `ai_catalog_audit_actor_fk`
        FOREIGN KEY (`actor_app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Audit history of AI provider and model catalog changes';
