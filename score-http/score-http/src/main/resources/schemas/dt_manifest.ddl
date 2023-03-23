CREATE TABLE `dt_manifest`
(
    `dt_manifest_id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `release_id`                 bigint(20) unsigned NOT NULL,
    `dt_id`                      bigint(20) unsigned NOT NULL,
    `based_dt_manifest_id`       bigint(20) unsigned          DEFAULT NULL,
    `conflict`                   tinyint(1)          NOT NULL DEFAULT '0' COMMENT 'This indicates that there is a conflict between self and relationship.',
    `log_id`                     bigint(20) unsigned          DEFAULT NULL COMMENT 'A foreign key pointed to a log for the current record.',
    `replacement_dt_manifest_id` bigint(20) unsigned          DEFAULT NULL COMMENT 'This refers to a replacement manifest if the record is deprecated.',
    `prev_dt_manifest_id`        bigint(20) unsigned          DEFAULT NULL,
    `next_dt_manifest_id`        bigint(20) unsigned          DEFAULT NULL,
    PRIMARY KEY (`dt_manifest_id`),
    KEY `dt_manifest_dt_id_fk` (`dt_id`),
    KEY `dt_manifest_release_id_fk` (`release_id`),
    KEY `dt_manifest_log_id_fk` (`log_id`),
    KEY `dt_manifest_prev_dt_manifest_id_fk` (`prev_dt_manifest_id`),
    KEY `dt_manifest_next_dt_manifest_id_fk` (`next_dt_manifest_id`),
    KEY `dt_replacement_dt_manifest_id_fk` (`replacement_dt_manifest_id`),
    KEY `dt_manifest_based_dt_manifest_id_fk` (`based_dt_manifest_id`),
    CONSTRAINT `dt_manifest_based_dt_manifest_id_fk` FOREIGN KEY (`based_dt_manifest_id`) REFERENCES `dt_manifest` (`dt_manifest_id`),
    CONSTRAINT `dt_manifest_dt_id_fk` FOREIGN KEY (`dt_id`) REFERENCES `dt` (`dt_id`),
    CONSTRAINT `dt_manifest_log_id_fk` FOREIGN KEY (`log_id`) REFERENCES `log` (`log_id`),
    CONSTRAINT `dt_manifest_next_dt_manifest_id_fk` FOREIGN KEY (`next_dt_manifest_id`) REFERENCES `dt_manifest` (`dt_manifest_id`),
    CONSTRAINT `dt_manifest_prev_dt_manifest_id_fk` FOREIGN KEY (`prev_dt_manifest_id`) REFERENCES `dt_manifest` (`dt_manifest_id`),
    CONSTRAINT `dt_manifest_release_id_fk` FOREIGN KEY (`release_id`) REFERENCES `release` (`release_id`),
    CONSTRAINT `dt_replacement_dt_manifest_id_fk` FOREIGN KEY (`replacement_dt_manifest_id`) REFERENCES `dt_manifest` (`dt_manifest_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;