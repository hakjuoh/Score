CREATE TABLE `release`
(
    `release_id`            bigint(20) unsigned          NOT NULL AUTO_INCREMENT COMMENT 'RELEASE_ID must be an incremental integer. RELEASE_ID that is more than another RELEASE_ID is interpreted to be released later than the other.',
    `guid`                  char(32) CHARACTER SET ascii NOT NULL COMMENT 'A globally unique identifier (GUID).',
    `release_num`           varchar(45)         DEFAULT NULL COMMENT 'Release number such has 10.0, 10.1, etc. ',
    `release_note`          longtext COMMENT 'Description or note associated with the release.',
    `release_license`       longtext COMMENT 'License associated with the release.',
    `namespace_id`          bigint(20) unsigned DEFAULT NULL COMMENT 'Foreign key to the NAMESPACE table. It identifies the namespace used with the release. It is particularly useful for a library that uses a single namespace such like the OAGIS 10.x. A library that uses multiple namespace but has a main namespace may also use this column as a specific namespace can be override at the module level.',
    `created_by`            bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the APP_USER table identifying user who created the namespace.',
    `last_updated_by`       bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the APP_USER table identifying the user who last updated the record.',
    `creation_timestamp`    datetime(6)                  NOT NULL COMMENT 'The timestamp when the record was first created.',
    `last_update_timestamp` datetime(6)                  NOT NULL COMMENT 'The timestamp when the record was last updated.',
    `state`                 varchar(20)         DEFAULT 'Initialized' COMMENT 'This indicates the revision life cycle state of the Release.',
    PRIMARY KEY (`release_id`),
    KEY `release_namespace_id_fk` (`namespace_id`),
    KEY `release_created_by_fk` (`created_by`),
    KEY `release_last_updated_by_fk` (`last_updated_by`),
    CONSTRAINT `release_created_by_fk` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `release_last_updated_by_fk` FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `release_namespace_id_fk` FOREIGN KEY (`namespace_id`) REFERENCES `namespace` (`namespace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8 COMMENT ='The is table store the release information.';