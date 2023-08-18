CREATE TABLE `ascc_manifest`
(
    `ascc_manifest_id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `release_id`                   bigint(20) unsigned DEFAULT NULL,
    `ascc_id`                      bigint(20) unsigned NOT NULL,
    `seq_key_id`                   bigint(20) unsigned DEFAULT NULL,
    `from_acc_manifest_id`         bigint(20) unsigned NOT NULL,
    `to_asccp_manifest_id`         bigint(20) unsigned NOT NULL,
    `conflict`                     tinyint(1) NOT NULL DEFAULT 0 COMMENT 'This indicates that there is a conflict between self and relationship.',
    `replacement_ascc_manifest_id` bigint(20) unsigned DEFAULT NULL COMMENT 'This refers to a replacement manifest if the record is deprecated.',
    `prev_ascc_manifest_id`        bigint(20) unsigned DEFAULT NULL,
    `next_ascc_manifest_id`        bigint(20) unsigned DEFAULT NULL,
    PRIMARY KEY (`ascc_manifest_id`),
    KEY                            `ascc_manifest_ascc_id_fk` (`ascc_id`),
    KEY                            `ascc_manifest_release_id_fk` (`release_id`),
    KEY                            `ascc_manifest_from_acc_manifest_id_fk` (`from_acc_manifest_id`),
    KEY                            `ascc_manifest_to_asccp_manifest_id_fk` (`to_asccp_manifest_id`),
    KEY                            `ascc_manifest_prev_ascc_manifest_id_fk` (`prev_ascc_manifest_id`),
    KEY                            `ascc_manifest_next_ascc_manifest_id_fk` (`next_ascc_manifest_id`),
    KEY                            `ascc_manifest_seq_key_id_fk` (`seq_key_id`),
    KEY                            `ascc_replacement_ascc_manifest_id_fk` (`replacement_ascc_manifest_id`),
    CONSTRAINT `ascc_manifest_ascc_id_fk` FOREIGN KEY (`ascc_id`) REFERENCES `ascc` (`ascc_id`),
    CONSTRAINT `ascc_manifest_from_acc_manifest_id_fk` FOREIGN KEY (`from_acc_manifest_id`) REFERENCES `acc_manifest` (`acc_manifest_id`),
    CONSTRAINT `ascc_manifest_next_ascc_manifest_id_fk` FOREIGN KEY (`next_ascc_manifest_id`) REFERENCES `ascc_manifest` (`ascc_manifest_id`),
    CONSTRAINT `ascc_manifest_prev_ascc_manifest_id_fk` FOREIGN KEY (`prev_ascc_manifest_id`) REFERENCES `ascc_manifest` (`ascc_manifest_id`),
    CONSTRAINT `ascc_manifest_release_id_fk` FOREIGN KEY (`release_id`) REFERENCES `release` (`release_id`),
    CONSTRAINT `ascc_manifest_seq_key_id_fk` FOREIGN KEY (`seq_key_id`) REFERENCES `seq_key` (`seq_key_id`),
    CONSTRAINT `ascc_manifest_to_asccp_manifest_id_fk` FOREIGN KEY (`to_asccp_manifest_id`) REFERENCES `asccp_manifest` (`asccp_manifest_id`),
    CONSTRAINT `ascc_replacement_ascc_manifest_id_fk` FOREIGN KEY (`replacement_ascc_manifest_id`) REFERENCES `ascc_manifest` (`ascc_manifest_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;