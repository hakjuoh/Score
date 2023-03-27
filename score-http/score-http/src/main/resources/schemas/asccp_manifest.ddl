CREATE TABLE `asccp_manifest`
(
    `asccp_manifest_id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `release_id`                    bigint(20) unsigned NOT NULL,
    `asccp_id`                      bigint(20) unsigned NOT NULL,
    `role_of_acc_manifest_id`       bigint(20) unsigned NOT NULL,
    `conflict`                      tinyint(1)          NOT NULL DEFAULT '0' COMMENT 'This indicates that there is a conflict between self and relationship.',
    `log_id`                        bigint(20) unsigned          DEFAULT NULL COMMENT 'A foreign key pointed to a log for the current record.',
    `replacement_asccp_manifest_id` bigint(20) unsigned          DEFAULT NULL COMMENT 'This refers to a replacement manifest if the record is deprecated.',
    `prev_asccp_manifest_id`        bigint(20) unsigned          DEFAULT NULL,
    `next_asccp_manifest_id`        bigint(20) unsigned          DEFAULT NULL,
    PRIMARY KEY (`asccp_manifest_id`),
    KEY `asccp_manifest_asccp_id_fk` (`asccp_id`),
    KEY `asccp_manifest_role_of_acc_manifest_id_fk` (`role_of_acc_manifest_id`),
    KEY `asccp_manifest_release_id_fk` (`release_id`),
    KEY `asccp_manifest_log_id_fk` (`log_id`),
    KEY `asccp_manifest_prev_asccp_manifest_id_fk` (`prev_asccp_manifest_id`),
    KEY `asccp_manifest_next_asccp_manifest_id_fk` (`next_asccp_manifest_id`),
    KEY `asccp_replacement_asccp_manifest_id_fk` (`replacement_asccp_manifest_id`),
    CONSTRAINT `asccp_manifest_asccp_id_fk` FOREIGN KEY (`asccp_id`) REFERENCES `asccp` (`asccp_id`),
    CONSTRAINT `asccp_manifest_log_id_fk` FOREIGN KEY (`log_id`) REFERENCES `log` (`log_id`),
    CONSTRAINT `asccp_manifest_next_asccp_manifest_id_fk` FOREIGN KEY (`next_asccp_manifest_id`) REFERENCES `asccp_manifest` (`asccp_manifest_id`),
    CONSTRAINT `asccp_manifest_prev_asccp_manifest_id_fk` FOREIGN KEY (`prev_asccp_manifest_id`) REFERENCES `asccp_manifest` (`asccp_manifest_id`),
    CONSTRAINT `asccp_manifest_release_id_fk` FOREIGN KEY (`release_id`) REFERENCES `release` (`release_id`),
    CONSTRAINT `asccp_manifest_role_of_acc_manifest_id_fk` FOREIGN KEY (`role_of_acc_manifest_id`) REFERENCES `acc_manifest` (`acc_manifest_id`),
    CONSTRAINT `asccp_replacement_asccp_manifest_id_fk` FOREIGN KEY (`replacement_asccp_manifest_id`) REFERENCES `asccp_manifest` (`asccp_manifest_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;