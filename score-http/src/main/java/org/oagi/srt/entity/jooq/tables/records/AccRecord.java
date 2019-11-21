/*
 * This file is generated by jOOQ.
 */
package org.oagi.srt.entity.jooq.tables.records;


import java.sql.Timestamp;

import javax.annotation.Generated;

import org.jooq.Record1;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.srt.entity.jooq.tables.Acc;


/**
 * The ACC table holds information about complex data structured concepts. 
 * For example, OAGIS's Components, Nouns, and BODs are captured in the ACC 
 * table.
 * 
 * Note that only Extension is supported when deriving ACC from another ACC. 
 * (So if there is a restriction needed, maybe that concept should placed 
 * higher in the derivation hierarchy rather than lower.)
 * 
 * In OAGIS, all XSD extensions will be treated as a qualification of an ACC.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.12.1"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AccRecord extends UpdatableRecordImpl<AccRecord> {

    private static final long serialVersionUID = -616480238;

    /**
     * Setter for <code>oagi.acc.acc_id</code>. A internal, primary database key of an ACC.
     */
    public void setAccId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for <code>oagi.acc.acc_id</code>. A internal, primary database key of an ACC.
     */
    public ULong getAccId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.acc.guid</code>. A globally unique identifier (GUID) of an ACC. Per OAGIS, a GUID is of the form "oagis-id-" followed by a 32 Hex character sequence.
     */
    public void setGuid(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.acc.guid</code>. A globally unique identifier (GUID) of an ACC. Per OAGIS, a GUID is of the form "oagis-id-" followed by a 32 Hex character sequence.
     */
    public String getGuid() {
        return (String) get(1);
    }

    /**
     * Setter for <code>oagi.acc.object_class_term</code>. Object class name of the ACC concept. For OAGIS, this is generally name of a type with the "Type" truncated from the end. Per CCS the name is space separated. "ID" is expanded to "Identifier".
     */
    public void setObjectClassTerm(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.acc.object_class_term</code>. Object class name of the ACC concept. For OAGIS, this is generally name of a type with the "Type" truncated from the end. Per CCS the name is space separated. "ID" is expanded to "Identifier".
     */
    public String getObjectClassTerm() {
        return (String) get(2);
    }

    /**
     * Setter for <code>oagi.acc.den</code>. DEN (dictionary entry name) of the ACC. It can be derived as OBJECT_CLASS_QUALIFIER + "_ " + OBJECT_CLASS_TERM + ". Details".
     */
    public void setDen(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>oagi.acc.den</code>. DEN (dictionary entry name) of the ACC. It can be derived as OBJECT_CLASS_QUALIFIER + "_ " + OBJECT_CLASS_TERM + ". Details".
     */
    public String getDen() {
        return (String) get(3);
    }

    /**
     * Setter for <code>oagi.acc.definition</code>. This is a documentation or description of the ACC. Since ACC is business context independent, this is a business context independent description of the ACC concept.
     */
    public void setDefinition(String value) {
        set(4, value);
    }

    /**
     * Getter for <code>oagi.acc.definition</code>. This is a documentation or description of the ACC. Since ACC is business context independent, this is a business context independent description of the ACC concept.
     */
    public String getDefinition() {
        return (String) get(4);
    }

    /**
     * Setter for <code>oagi.acc.definition_source</code>. This is typically a URL identifying the source of the DEFINITION column.
     */
    public void setDefinitionSource(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>oagi.acc.definition_source</code>. This is typically a URL identifying the source of the DEFINITION column.
     */
    public String getDefinitionSource() {
        return (String) get(5);
    }

    /**
     * Setter for <code>oagi.acc.based_acc_id</code>. BASED_ACC_ID is a foreign key to the ACC table itself. It represents the ACC that is qualified by this ACC. In general CCS sense, a qualification can be a content extension or restriction, but the current scope supports only extension.
     */
    public void setBasedAccId(ULong value) {
        set(6, value);
    }

    /**
     * Getter for <code>oagi.acc.based_acc_id</code>. BASED_ACC_ID is a foreign key to the ACC table itself. It represents the ACC that is qualified by this ACC. In general CCS sense, a qualification can be a content extension or restriction, but the current scope supports only extension.
     */
    public ULong getBasedAccId() {
        return (ULong) get(6);
    }

    /**
     * Setter for <code>oagi.acc.object_class_qualifier</code>. This column stores the qualifier of an ACC, particularly when it has a based ACC. 
     */
    public void setObjectClassQualifier(String value) {
        set(7, value);
    }

    /**
     * Getter for <code>oagi.acc.object_class_qualifier</code>. This column stores the qualifier of an ACC, particularly when it has a based ACC. 
     */
    public String getObjectClassQualifier() {
        return (String) get(7);
    }

    /**
     * Setter for <code>oagi.acc.oagis_component_type</code>. The value can be 0 = BASE, 1 = SEMANTICS, 2 = EXTENSION, 3 = SEMANTIC_GROUP, 4 = USER_EXTENSION_GROUP, 5 = EMBEDDED. Generally, BASE is assigned when the OBJECT_CLASS_TERM contains "Base" at the end. EXTENSION is assigned with the OBJECT_CLASS_TERM contains "Extension" at the end. SEMANTIC_GROUP is assigned when an ACC is imported from an XSD Group. USER_EXTENSION_GROUP is a wrapper ACC (a virtual ACC) for segregating user's extension content. EMBEDDED is used for an ACC whose content is not explicitly defined in the database, for example, the Any Structured Content ACC that corresponds to the xsd:any.  Other cases are assigned SEMANTICS. 
     */
    public void setOagisComponentType(Integer value) {
        set(8, value);
    }

    /**
     * Getter for <code>oagi.acc.oagis_component_type</code>. The value can be 0 = BASE, 1 = SEMANTICS, 2 = EXTENSION, 3 = SEMANTIC_GROUP, 4 = USER_EXTENSION_GROUP, 5 = EMBEDDED. Generally, BASE is assigned when the OBJECT_CLASS_TERM contains "Base" at the end. EXTENSION is assigned with the OBJECT_CLASS_TERM contains "Extension" at the end. SEMANTIC_GROUP is assigned when an ACC is imported from an XSD Group. USER_EXTENSION_GROUP is a wrapper ACC (a virtual ACC) for segregating user's extension content. EMBEDDED is used for an ACC whose content is not explicitly defined in the database, for example, the Any Structured Content ACC that corresponds to the xsd:any.  Other cases are assigned SEMANTICS. 
     */
    public Integer getOagisComponentType() {
        return (Integer) get(8);
    }

    /**
     * Setter for <code>oagi.acc.module_id</code>. Foreign key to the module table indicating the physical schema the ACC belongs to.
     */
    public void setModuleId(ULong value) {
        set(9, value);
    }

    /**
     * Getter for <code>oagi.acc.module_id</code>. Foreign key to the module table indicating the physical schema the ACC belongs to.
     */
    public ULong getModuleId() {
        return (ULong) get(9);
    }

    /**
     * Setter for <code>oagi.acc.namespace_id</code>. Foreign key to the NAMESPACE table. This is the namespace to which the entity belongs. This namespace column is primarily used in the case the component is a user's component because there is also a namespace assigned at the release level.
     */
    public void setNamespaceId(ULong value) {
        set(10, value);
    }

    /**
     * Getter for <code>oagi.acc.namespace_id</code>. Foreign key to the NAMESPACE table. This is the namespace to which the entity belongs. This namespace column is primarily used in the case the component is a user's component because there is also a namespace assigned at the release level.
     */
    public ULong getNamespaceId() {
        return (ULong) get(10);
    }

    /**
     * Setter for <code>oagi.acc.created_by</code>. Foreign key to the APP_USER table referring to the user who creates the entity.\n\nThis column never change between the history and the current record for a given revision. The history record should have the same value as that of its current record.
     */
    public void setCreatedBy(ULong value) {
        set(11, value);
    }

    /**
     * Getter for <code>oagi.acc.created_by</code>. Foreign key to the APP_USER table referring to the user who creates the entity.\n\nThis column never change between the history and the current record for a given revision. The history record should have the same value as that of its current record.
     */
    public ULong getCreatedBy() {
        return (ULong) get(11);
    }

    /**
     * Setter for <code>oagi.acc.owner_user_id</code>. Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\n\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership. 
     */
    public void setOwnerUserId(ULong value) {
        set(12, value);
    }

    /**
     * Getter for <code>oagi.acc.owner_user_id</code>. Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\n\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership. 
     */
    public ULong getOwnerUserId() {
        return (ULong) get(12);
    }

    /**
     * Setter for <code>oagi.acc.last_updated_by</code>. Foreign key to the APP_USER table referring to the last user who updated the record. \n\nIn the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).
     */
    public void setLastUpdatedBy(ULong value) {
        set(13, value);
    }

    /**
     * Getter for <code>oagi.acc.last_updated_by</code>. Foreign key to the APP_USER table referring to the last user who updated the record. \n\nIn the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).
     */
    public ULong getLastUpdatedBy() {
        return (ULong) get(13);
    }

    /**
     * Setter for <code>oagi.acc.creation_timestamp</code>. Timestamp when the revision of the ACC was created. \n\nThis never change for a revision.
     */
    public void setCreationTimestamp(Timestamp value) {
        set(14, value);
    }

    /**
     * Getter for <code>oagi.acc.creation_timestamp</code>. Timestamp when the revision of the ACC was created. \n\nThis never change for a revision.
     */
    public Timestamp getCreationTimestamp() {
        return (Timestamp) get(14);
    }

    /**
     * Setter for <code>oagi.acc.last_update_timestamp</code>. The timestamp when the record was last updated.\n\nThe value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the revision has occurred.
     */
    public void setLastUpdateTimestamp(Timestamp value) {
        set(15, value);
    }

    /**
     * Getter for <code>oagi.acc.last_update_timestamp</code>. The timestamp when the record was last updated.\n\nThe value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the revision has occurred.
     */
    public Timestamp getLastUpdateTimestamp() {
        return (Timestamp) get(15);
    }

    /**
     * Setter for <code>oagi.acc.state</code>. 1 = EDITING, 2 = CANDIDATE, 3 = PUBLISHED. This the revision life cycle state of the ACC.

State change can't be undone. But the history record can still keep the records of when the state was changed.
     */
    public void setState(Integer value) {
        set(16, value);
    }

    /**
     * Getter for <code>oagi.acc.state</code>. 1 = EDITING, 2 = CANDIDATE, 3 = PUBLISHED. This the revision life cycle state of the ACC.

State change can't be undone. But the history record can still keep the records of when the state was changed.
     */
    public Integer getState() {
        return (Integer) get(16);
    }

    /**
     * Setter for <code>oagi.acc.revision_num</code>. REVISION_NUM is an incremental integer. It tracks changes in each component. If a change is made to a component after it has been published, the component receives a new revision number. Revision number can be 0, 1, 2, and so on. A record with zero revision number reflects the current record of the component (the identity of a component in this case is its GUID or the primary key).
     */
    public void setRevisionNum(Integer value) {
        set(17, value);
    }

    /**
     * Getter for <code>oagi.acc.revision_num</code>. REVISION_NUM is an incremental integer. It tracks changes in each component. If a change is made to a component after it has been published, the component receives a new revision number. Revision number can be 0, 1, 2, and so on. A record with zero revision number reflects the current record of the component (the identity of a component in this case is its GUID or the primary key).
     */
    public Integer getRevisionNum() {
        return (Integer) get(17);
    }

    /**
     * Setter for <code>oagi.acc.revision_tracking_num</code>. REVISION_TRACKING_NUM supports the ability to undo changes during a revision (life cycle of a revision is from the component's EDITING state to PUBLISHED state). Once the component has transitioned into the PUBLISHED state for its particular revision, all revision tracking records are deleted except the latest one. REVISION_TRACKING_NUMB can be 0, 1, 2, and so on. The zero value is assigned to the record with REVISION_NUM = 0 as a default.
     */
    public void setRevisionTrackingNum(Integer value) {
        set(18, value);
    }

    /**
     * Getter for <code>oagi.acc.revision_tracking_num</code>. REVISION_TRACKING_NUM supports the ability to undo changes during a revision (life cycle of a revision is from the component's EDITING state to PUBLISHED state). Once the component has transitioned into the PUBLISHED state for its particular revision, all revision tracking records are deleted except the latest one. REVISION_TRACKING_NUMB can be 0, 1, 2, and so on. The zero value is assigned to the record with REVISION_NUM = 0 as a default.
     */
    public Integer getRevisionTrackingNum() {
        return (Integer) get(18);
    }

    /**
     * Setter for <code>oagi.acc.revision_action</code>. This indicates the action associated with the record. The action can be 1 = INSERT, 2 = UPDATE, and 3 = DELETE. This column is null for the current record.
     */
    public void setRevisionAction(Byte value) {
        set(19, value);
    }

    /**
     * Getter for <code>oagi.acc.revision_action</code>. This indicates the action associated with the record. The action can be 1 = INSERT, 2 = UPDATE, and 3 = DELETE. This column is null for the current record.
     */
    public Byte getRevisionAction() {
        return (Byte) get(19);
    }

    /**
     * Setter for <code>oagi.acc.release_id</code>. RELEASE_ID is an incremental integer. It is an unformatted counter part of the RELEASE_NUMBER in the RELEASE table. RELEASE_ID can be 1, 2, 3, and so on. A release ID indicates the release point when a particular component revision is released. A component revision is only released once and assumed to be included in the subsequent releases unless it has been deleted (as indicated by the REVISION_ACTION column).\n\nNot all component revisions have an associated RELEASE_ID because some revisions may never be released. USER_EXTENSION_GROUP component type is never part of a release.\n\nUnpublished components cannot be released.\n\nThis column is NULL for the current record.
     */
    public void setReleaseId(ULong value) {
        set(20, value);
    }

    /**
     * Getter for <code>oagi.acc.release_id</code>. RELEASE_ID is an incremental integer. It is an unformatted counter part of the RELEASE_NUMBER in the RELEASE table. RELEASE_ID can be 1, 2, 3, and so on. A release ID indicates the release point when a particular component revision is released. A component revision is only released once and assumed to be included in the subsequent releases unless it has been deleted (as indicated by the REVISION_ACTION column).\n\nNot all component revisions have an associated RELEASE_ID because some revisions may never be released. USER_EXTENSION_GROUP component type is never part of a release.\n\nUnpublished components cannot be released.\n\nThis column is NULL for the current record.
     */
    public ULong getReleaseId() {
        return (ULong) get(20);
    }

    /**
     * Setter for <code>oagi.acc.current_acc_id</code>. This is a self-foreign-key. It points from a revised record to the current record. The current record is denoted by the the record whose REVISION_NUM is 0. Revised records (a.k.a. history records) and their current record must have the same GUID.\n\nIt is noted that although this is a foreign key by definition, we don't specify a foreign key in the data model. This is because when an entity is deleted the current record won't exist anymore.\n\nThe value of this column for the current record should be left NULL.
     */
    public void setCurrentAccId(ULong value) {
        set(21, value);
    }

    /**
     * Getter for <code>oagi.acc.current_acc_id</code>. This is a self-foreign-key. It points from a revised record to the current record. The current record is denoted by the the record whose REVISION_NUM is 0. Revised records (a.k.a. history records) and their current record must have the same GUID.\n\nIt is noted that although this is a foreign key by definition, we don't specify a foreign key in the data model. This is because when an entity is deleted the current record won't exist anymore.\n\nThe value of this column for the current record should be left NULL.
     */
    public ULong getCurrentAccId() {
        return (ULong) get(21);
    }

    /**
     * Setter for <code>oagi.acc.is_deprecated</code>. Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be allowed).
     */
    public void setIsDeprecated(Byte value) {
        set(22, value);
    }

    /**
     * Getter for <code>oagi.acc.is_deprecated</code>. Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be allowed).
     */
    public Byte getIsDeprecated() {
        return (Byte) get(22);
    }

    /**
     * Setter for <code>oagi.acc.is_abstract</code>. This is the XML Schema abstract flag. Default is false. If it is true, the abstract flag will be set to true when generating a corresponding xsd:complexType. So although this flag may not apply to some ACCs such as those that are xsd:group. It is still have a false value.
     */
    public void setIsAbstract(Byte value) {
        set(23, value);
    }

    /**
     * Getter for <code>oagi.acc.is_abstract</code>. This is the XML Schema abstract flag. Default is false. If it is true, the abstract flag will be set to true when generating a corresponding xsd:complexType. So although this flag may not apply to some ACCs such as those that are xsd:group. It is still have a false value.
     */
    public Byte getIsAbstract() {
        return (Byte) get(23);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached AccRecord
     */
    public AccRecord() {
        super(Acc.ACC);
    }

    /**
     * Create a detached, initialised AccRecord
     */
    public AccRecord(ULong accId, String guid, String objectClassTerm, String den, String definition, String definitionSource, ULong basedAccId, String objectClassQualifier, Integer oagisComponentType, ULong moduleId, ULong namespaceId, ULong createdBy, ULong ownerUserId, ULong lastUpdatedBy, Timestamp creationTimestamp, Timestamp lastUpdateTimestamp, Integer state, Integer revisionNum, Integer revisionTrackingNum, Byte revisionAction, ULong releaseId, ULong currentAccId, Byte isDeprecated, Byte isAbstract) {
        super(Acc.ACC);

        set(0, accId);
        set(1, guid);
        set(2, objectClassTerm);
        set(3, den);
        set(4, definition);
        set(5, definitionSource);
        set(6, basedAccId);
        set(7, objectClassQualifier);
        set(8, oagisComponentType);
        set(9, moduleId);
        set(10, namespaceId);
        set(11, createdBy);
        set(12, ownerUserId);
        set(13, lastUpdatedBy);
        set(14, creationTimestamp);
        set(15, lastUpdateTimestamp);
        set(16, state);
        set(17, revisionNum);
        set(18, revisionTrackingNum);
        set(19, revisionAction);
        set(20, releaseId);
        set(21, currentAccId);
        set(22, isDeprecated);
        set(23, isAbstract);
    }
}