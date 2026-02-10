-- ----------------------------------------------------
-- Migration script for Score v3.4.2                 --
--                                                   --
-- Author: Hakju Oh <hakju.oh@nist.gov>              --
-- ----------------------------------------------------

SET FOREIGN_KEY_CHECKS = 0;

-- Issue #1678
ALTER TABLE `bie_package`
    ADD COLUMN `guid` varchar(32) NOT NULL COMMENT 'Unique identifier of this BIE package.' AFTER `bie_package_id`,
    ADD COLUMN `prev_bie_package_id` bigint(20) unsigned COMMENT 'A foreign key referring to the previous version of this BIE package, if any. Used to track package version history.' AFTER `state`,
    ADD CONSTRAINT `bie_package_prev_bie_package_id_fk` FOREIGN KEY (`prev_bie_package_id`) REFERENCES `bie_package` (`bie_package_id`);

ALTER TABLE `bie_package_top_level_asbiep`
    ADD COLUMN `prev_top_level_asbiep_id` bigint(20) unsigned COMMENT 'A foreign key referring to the previous version of the Top-Level ASBIEP record, if any. Used to track version history within the BIE package.' AFTER `top_level_asbiep_id`,
    ADD CONSTRAINT `bie_package_top_level_asbiep_prev_top_level_asbiep_id_fk` FOREIGN KEY (`prev_top_level_asbiep_id`) REFERENCES `top_level_asbiep` (`top_level_asbiep_id`);

-- Issue #1659
DROP TABLE IF EXISTS `asbiep_support_doc`;
CREATE TABLE `asbiep_support_doc`
(
    `asbiep_support_doc_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'Primary key. Unique identifier for each supporting documentation.',
    `asbiep_id`             bigint(20) unsigned NOT NULL COMMENT 'Foreign key. References the related ASBIEP record.',
    `content`               text                DEFAULT NULL COMMENT 'The main body or text content of the supporting documentation.',
    `description`           text                DEFAULT NULL COMMENT 'Optional description, summary, or metadata about the supporting documentation.',
    PRIMARY KEY (`asbiep_support_doc_id`),
    CONSTRAINT `asbiep_support_doc_asbiep_id_fk` FOREIGN KEY (`asbiep_id`) REFERENCES `asbiep` (`asbiep_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci
    COMMENT ='Table storing supporting documentations linked to ASBIEP records.';

SET FOREIGN_KEY_CHECKS = 1;