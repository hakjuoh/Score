CREATE TABLE `ai_user_policy_audit`
(
    `ai_user_policy_audit_id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the AI user policy audit entry',
    `target_app_user_id`      bigint(20) unsigned NOT NULL COMMENT 'Identifier of the user whose policy was changed',
    `actor_app_user_id`       bigint(20) unsigned NULL COMMENT 'Identifier of the administrator who changed the policy',
    `action`                  varchar(16) NOT NULL COMMENT 'Policy change action: CREATE, UPDATE, or DELETE',
    `before_json`             JSON NULL COMMENT 'Canonical policy snapshot before the change',
    `after_json`              JSON NULL COMMENT 'Canonical policy snapshot after the change',
    `reason`                  varchar(500) NULL COMMENT 'Reason or administrator comment for the policy change',
    `creation_timestamp`     datetime(6) NOT NULL COMMENT 'Date and time when the policy was changed',
    PRIMARY KEY (`ai_user_policy_audit_id`),
    KEY `ai_user_policy_audit_target_time_idx` (`target_app_user_id`, `creation_timestamp`),
    CONSTRAINT `ai_user_policy_audit_actor_fk`
        FOREIGN KEY (`actor_app_user_id`) REFERENCES `app_user` (`app_user_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Audit history of AI user policy changes';
