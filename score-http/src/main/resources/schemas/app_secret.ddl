CREATE TABLE `app_secret`
(
    `app_secret_id`      bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Identifier of the encrypted application secret',
    `secret_guid`        char(36) COLLATE ascii_bin NOT NULL COMMENT 'Stable UUID used as authenticated encryption context',
    `secret_name`        varchar(255) NOT NULL COMMENT 'Unique logical name of the application secret',
    `secret_type`        varchar(32) NOT NULL COMMENT 'Secret type such as AI_PROVIDER_API_KEY',
    `encrypted_value`    mediumblob NOT NULL COMMENT 'AES-256-GCM ciphertext including the authentication tag',
    `nonce`              binary(12) NOT NULL COMMENT 'Unique 96-bit AES-GCM nonce for this encrypted value',
    `encryption_key_id`  varchar(64) NOT NULL COMMENT 'Identifier of the application encryption key used for this value',
    `encryption_version` smallint unsigned NOT NULL DEFAULT 1 COMMENT 'Version of the encryption payload format',
    `created_by`         bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who created the secret',
    `last_updated_by`    bigint(20) unsigned NOT NULL COMMENT 'Identifier of the administrator who last updated the secret',
    `creation_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the secret was created',
    `last_update_timestamp` datetime(6) NOT NULL COMMENT 'Date and time when the secret was last updated',
    PRIMARY KEY (`app_secret_id`),
    UNIQUE KEY `app_secret_guid_uk` (`secret_guid`),
    UNIQUE KEY `app_secret_name_uk` (`secret_name`),
    CONSTRAINT `app_secret_created_by_fk`
        FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `app_secret_last_updated_by_fk`
        FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Encrypted application secrets; initially limited to AI provider API keys';
