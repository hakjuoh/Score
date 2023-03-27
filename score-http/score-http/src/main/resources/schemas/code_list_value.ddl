CREATE TABLE `code_list_value`
(
    `code_list_value_id`             bigint(20) unsigned          NOT NULL AUTO_INCREMENT COMMENT 'Internal, primary database key.',
    `guid`                           char(32) CHARACTER SET ascii NOT NULL COMMENT 'A globally unique identifier (GUID).',
    `code_list_id`                   bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the CODE_LIST table. It indicates the code list this code value belonging to.',
    `based_code_list_value_id`       bigint(20) unsigned                   DEFAULT NULL COMMENT 'Foreign key to the CODE_LIST_VALUE table itself. This column is used when the CODE_LIST is derived from the based CODE_LIST.',
    `value`                          tinytext                     NOT NULL COMMENT 'The code list value used in the instance data, e.g., EA, US-EN.',
    `meaning`                        varchar(100)                          DEFAULT NULL COMMENT 'The description or explanation of the code list value, e.g., ''Each'' for EA, ''English'' for EN.',
    `definition`                     text COMMENT 'Long description or explannation of the code list value, e.g., ''EA is a discrete quantity for counting each unit of an item, such as, 2 shampoo bottles, 3 box of cereals''.',
    `definition_source`              varchar(100)                          DEFAULT NULL COMMENT 'This is typically a URL identifying the source of the DEFINITION column.',
    `is_deprecated`                  tinyint(1)                            DEFAULT '0' COMMENT 'Indicates whether the code list value is deprecated and should not be reused (i.e., no new reference to this record should be allowed).',
    `replacement_code_list_value_id` bigint(20) unsigned                   DEFAULT NULL COMMENT 'This refers to a replacement if the record is deprecated.',
    `created_by`                     bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the APP_USER table. It indicates the user who created the code list.',
    `owner_user_id`                  bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\n\nThe ownership can change throughout the history, but undoing shouldn''t rollback the ownership.',
    `last_updated_by`                bigint(20) unsigned          NOT NULL COMMENT 'Foreign key to the APP_USER table. It identifies the user who last updated the code list.',
    `creation_timestamp`             datetime(6)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Timestamp when the code list was created.',
    `last_update_timestamp`          datetime(6)                  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Timestamp when the code list was last updated.',
    `prev_code_list_value_id`        bigint(20) unsigned                   DEFAULT NULL COMMENT 'A self-foreign key to indicate the previous history record.',
    `next_code_list_value_id`        bigint(20) unsigned                   DEFAULT NULL COMMENT 'A self-foreign key to indicate the next history record.',
    PRIMARY KEY (`code_list_value_id`),
    KEY `code_list_value_code_list_id_fk` (`code_list_id`),
    KEY `code_list_value_created_by_fk` (`created_by`),
    KEY `code_list_value_owner_user_id_fk` (`owner_user_id`),
    KEY `code_list_value_last_updated_by_fk` (`last_updated_by`),
    KEY `code_list_value_prev_code_list_value_id_fk` (`prev_code_list_value_id`),
    KEY `code_list_value_next_code_list_value_id_fk` (`next_code_list_value_id`),
    KEY `code_list_value_replacement_code_list_value_id_fk` (`replacement_code_list_value_id`),
    KEY `code_list_value_based_code_list_value_id_fk` (`based_code_list_value_id`),
    CONSTRAINT `code_list_value_based_code_list_value_id_fk` FOREIGN KEY (`based_code_list_value_id`) REFERENCES `code_list_value` (`code_list_value_id`),
    CONSTRAINT `code_list_value_code_list_id_fk` FOREIGN KEY (`code_list_id`) REFERENCES `code_list` (`code_list_id`),
    CONSTRAINT `code_list_value_created_by_fk` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `code_list_value_last_updated_by_fk` FOREIGN KEY (`last_updated_by`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `code_list_value_next_code_list_value_id_fk` FOREIGN KEY (`next_code_list_value_id`) REFERENCES `code_list_value` (`code_list_value_id`),
    CONSTRAINT `code_list_value_owner_user_id_fk` FOREIGN KEY (`owner_user_id`) REFERENCES `app_user` (`app_user_id`),
    CONSTRAINT `code_list_value_prev_code_list_value_id_fk` FOREIGN KEY (`prev_code_list_value_id`) REFERENCES `code_list_value` (`code_list_value_id`),
    CONSTRAINT `code_list_value_replacement_code_list_value_id_fk` FOREIGN KEY (`replacement_code_list_value_id`) REFERENCES `code_list_value` (`code_list_value_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8 COMMENT ='Each record in this table stores a code list value of a code list. A code list value may be inherited from another code list on which it is based. However, inherited value may be restricted (i.e., disabled and cannot be used) in this code list, i.e., the USED_INDICATOR = false. If the value cannot be used since the based code list, then the LOCKED_INDICATOR = TRUE, because the USED_INDICATOR of such code list value is FALSE by default and can no longer be changed.';