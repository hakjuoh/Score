/*
 * This file is generated by jOOQ.
 */
package org.oagi.srt.entity.jooq.tables;


import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Generated;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;
import org.jooq.types.ULong;
import org.oagi.srt.entity.jooq.Indexes;
import org.oagi.srt.entity.jooq.Keys;
import org.oagi.srt.entity.jooq.Oagi;
import org.oagi.srt.entity.jooq.tables.records.DtRecord;


/**
 * The DT table stores both CDT and BDT. The two types of DTs are differentiated 
 * by the TYPE column.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.12.1"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Dt extends TableImpl<DtRecord> {

    private static final long serialVersionUID = -1369951710;

    /**
     * The reference instance of <code>oagi.dt</code>
     */
    public static final Dt DT = new Dt();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<DtRecord> getRecordType() {
        return DtRecord.class;
    }

    /**
     * The column <code>oagi.dt.dt_id</code>. Internal, primary database key.
     */
    public final TableField<DtRecord, ULong> DT_ID = createField(DSL.name("dt_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Internal, primary database key.");

    /**
     * The column <code>oagi.dt.guid</code>. GUID of the data type. Per OAGIS, a GUID is of the form "oagis-id-" followed by a 32 Hex character sequence.
     */
    public final TableField<DtRecord, String> GUID = createField(DSL.name("guid"), org.jooq.impl.SQLDataType.VARCHAR(41).nullable(false), this, "GUID of the data type. Per OAGIS, a GUID is of the form \"oagis-id-\" followed by a 32 Hex character sequence.");

    /**
     * The column <code>oagi.dt.type</code>. List value: 0 = CDT, 1 = BDT.
     */
    public final TableField<DtRecord, Integer> TYPE = createField(DSL.name("type"), org.jooq.impl.SQLDataType.INTEGER, this, "List value: 0 = CDT, 1 = BDT.");

    /**
     * The column <code>oagi.dt.version_num</code>. Format X.Y.Z where all of them are integer with no leading zero allowed. X means major version number, Y means minor version number and Z means patch version number. This column is different from the REVISION_NUM column in that the new version is only assigned to the release component while the REVISION_NUM is assigned every time editing life cycle.
     */
    public final TableField<DtRecord, String> VERSION_NUM = createField(DSL.name("version_num"), org.jooq.impl.SQLDataType.VARCHAR(45).nullable(false), this, "Format X.Y.Z where all of them are integer with no leading zero allowed. X means major version number, Y means minor version number and Z means patch version number. This column is different from the REVISION_NUM column in that the new version is only assigned to the release component while the REVISION_NUM is assigned every time editing life cycle.");

    /**
     * The column <code>oagi.dt.previous_version_dt_id</code>. Foregin key to the DT table itself. It identifies the previous version.
     */
    public final TableField<DtRecord, ULong> PREVIOUS_VERSION_DT_ID = createField(DSL.name("previous_version_dt_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "Foregin key to the DT table itself. It identifies the previous version.");

    /**
     * The column <code>oagi.dt.data_type_term</code>. This is the data type term assigned to the DT. The allowed set of data type terms are defined in the DTC specification. This column is derived from the Based_DT_ID when the column is not blank. 
     */
    public final TableField<DtRecord, String> DATA_TYPE_TERM = createField(DSL.name("data_type_term"), org.jooq.impl.SQLDataType.VARCHAR(45), this, "This is the data type term assigned to the DT. The allowed set of data type terms are defined in the DTC specification. This column is derived from the Based_DT_ID when the column is not blank. ");

    /**
     * The column <code>oagi.dt.qualifier</code>. This column shall be blank when the DT_TYPE is CDT. When the DT_TYPE is BDT, this is optional. If the column is not blank it is a qualified BDT. If blank then the row may be a default BDT or an unqualified BDT. Default BDT is OAGIS concrete implementation of the CDT, these are the DT with numbers in the name, e.g., CodeType_1E7368 (DEN is 'Code_1E7368. Type'). Default BDTs are almost like permutation of the CDT options into concrete data types. Unqualified BDT is a BDT that OAGIS model schema generally used for its canonical. A handful of default BDTs were selected; and each of them is wrapped with another type definition that has a simpler name such as CodeType and NormalizedString type - we call these "unqualified BDTs". 
     */
    public final TableField<DtRecord, String> QUALIFIER = createField(DSL.name("qualifier"), org.jooq.impl.SQLDataType.VARCHAR(100), this, "This column shall be blank when the DT_TYPE is CDT. When the DT_TYPE is BDT, this is optional. If the column is not blank it is a qualified BDT. If blank then the row may be a default BDT or an unqualified BDT. Default BDT is OAGIS concrete implementation of the CDT, these are the DT with numbers in the name, e.g., CodeType_1E7368 (DEN is 'Code_1E7368. Type'). Default BDTs are almost like permutation of the CDT options into concrete data types. Unqualified BDT is a BDT that OAGIS model schema generally used for its canonical. A handful of default BDTs were selected; and each of them is wrapped with another type definition that has a simpler name such as CodeType and NormalizedString type - we call these \"unqualified BDTs\". ");

    /**
     * The column <code>oagi.dt.based_dt_id</code>. Foreign key pointing to the DT table itself. This column must be blank when the DT_TYPE is CDT. This column must not be blank when the DT_TYPE is BDT.
     */
    public final TableField<DtRecord, ULong> BASED_DT_ID = createField(DSL.name("based_dt_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "Foreign key pointing to the DT table itself. This column must be blank when the DT_TYPE is CDT. This column must not be blank when the DT_TYPE is BDT.");

    /**
     * The column <code>oagi.dt.den</code>. Dictionary Entry Name of the data type. 
     */
    public final TableField<DtRecord, String> DEN = createField(DSL.name("den"), org.jooq.impl.SQLDataType.VARCHAR(200).nullable(false), this, "Dictionary Entry Name of the data type. ");

    /**
     * The column <code>oagi.dt.content_component_den</code>. When the DT_TYPE is CDT this column is automatically derived from DATA_TYPE_TERM as "&lt;DATA_TYPE_TYPE&gt;. Content", where 'Content' is called property term of the content component according to CCTS. When the DT_TYPE is BDT this column has the same value as its BASED_DT_ID.
     */
    public final TableField<DtRecord, String> CONTENT_COMPONENT_DEN = createField(DSL.name("content_component_den"), org.jooq.impl.SQLDataType.VARCHAR(200), this, "When the DT_TYPE is CDT this column is automatically derived from DATA_TYPE_TERM as \"<DATA_TYPE_TYPE>. Content\", where 'Content' is called property term of the content component according to CCTS. When the DT_TYPE is BDT this column has the same value as its BASED_DT_ID.");

    /**
     * The column <code>oagi.dt.definition</code>. Description of the data type.
     */
    public final TableField<DtRecord, String> DEFINITION = createField(DSL.name("definition"), org.jooq.impl.SQLDataType.CLOB, this, "Description of the data type.");

    /**
     * The column <code>oagi.dt.definition_source</code>. This is typically a URL identifying the source of the DEFINITION column.
     */
    public final TableField<DtRecord, String> DEFINITION_SOURCE = createField(DSL.name("definition_source"), org.jooq.impl.SQLDataType.VARCHAR(200), this, "This is typically a URL identifying the source of the DEFINITION column.");

    /**
     * The column <code>oagi.dt.content_component_definition</code>. Description of the content component of the data type.
     */
    public final TableField<DtRecord, String> CONTENT_COMPONENT_DEFINITION = createField(DSL.name("content_component_definition"), org.jooq.impl.SQLDataType.CLOB, this, "Description of the content component of the data type.");

    /**
     * The column <code>oagi.dt.revision_doc</code>. This is for documenting about the revision, e.g., how the newer version of the DT is different from the previous version.
     */
    public final TableField<DtRecord, String> REVISION_DOC = createField(DSL.name("revision_doc"), org.jooq.impl.SQLDataType.CLOB, this, "This is for documenting about the revision, e.g., how the newer version of the DT is different from the previous version.");

    /**
     * The column <code>oagi.dt.state</code>. 1 = EDITING, 2 = CANDIDATE, 3 = PUBLISHED. This the revision life cycle state of the entity.\n\nState change can't be undone. But the history record can still keep the records of when the state was changed.
     */
    public final TableField<DtRecord, Integer> STATE = createField(DSL.name("state"), org.jooq.impl.SQLDataType.INTEGER, this, "1 = EDITING, 2 = CANDIDATE, 3 = PUBLISHED. This the revision life cycle state of the entity.\\n\\nState change can't be undone. But the history record can still keep the records of when the state was changed.");

    /**
     * The column <code>oagi.dt.module_id</code>. Foreign key to the MODULE table indicating physical file where the DT shall belong to when it is generated in an expression. 
     */
    public final TableField<DtRecord, ULong> MODULE_ID = createField(DSL.name("module_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "Foreign key to the MODULE table indicating physical file where the DT shall belong to when it is generated in an expression. ");

    /**
     * The column <code>oagi.dt.created_by</code>. Foreign key to the APP_USER table. It indicates the user who created this DT.
     */
    public final TableField<DtRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It indicates the user who created this DT.");

    /**
     * The column <code>oagi.dt.last_updated_by</code>. Foreign key to the APP_USER table referring to the last user who updated the record. 

In the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).
     */
    public final TableField<DtRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who updated the record. \n\nIn the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).");

    /**
     * The column <code>oagi.dt.owner_user_id</code>. Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\n\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership. 
     */
    public final TableField<DtRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\\n\\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership. ");

    /**
     * The column <code>oagi.dt.creation_timestamp</code>. Timestamp when the revision of the DT was created. 

This never change for a revision.
     */
    public final TableField<DtRecord, Timestamp> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), org.jooq.impl.SQLDataType.TIMESTAMP.nullable(false), this, "Timestamp when the revision of the DT was created. \n\nThis never change for a revision.");

    /**
     * The column <code>oagi.dt.last_update_timestamp</code>. Timestamp when the record was last updated.

The value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the revision has occurred.
     */
    public final TableField<DtRecord, Timestamp> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), org.jooq.impl.SQLDataType.TIMESTAMP.nullable(false), this, "Timestamp when the record was last updated.\n\nThe value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the revision has occurred.");

    /**
     * The column <code>oagi.dt.revision_num</code>. REVISION_NUM is an incremental integer. It tracks changes in each component. If a change is made to a component after it has been published, the component receives a new revision number. Revision number can be 0, 1, 2, and so on. A record with zero revision number reflects the current record of the component (the identity of a component in this case is its GUID or the primary key).
     */
    public final TableField<DtRecord, Integer> REVISION_NUM = createField(DSL.name("revision_num"), org.jooq.impl.SQLDataType.INTEGER.nullable(false).defaultValue(org.jooq.impl.DSL.inline("0", org.jooq.impl.SQLDataType.INTEGER)), this, "REVISION_NUM is an incremental integer. It tracks changes in each component. If a change is made to a component after it has been published, the component receives a new revision number. Revision number can be 0, 1, 2, and so on. A record with zero revision number reflects the current record of the component (the identity of a component in this case is its GUID or the primary key).");

    /**
     * The column <code>oagi.dt.revision_tracking_num</code>. REVISION_TRACKING_NUM supports the ability to undo changes during a revision (life cycle of a revision is from the component's EDITING state to PUBLISHED state). Once the component has transitioned into the PUBLISHED state for its particular revision, all revision tracking records are deleted except the latest one. REVISION_TRACKING_NUM can be 0, 1, 2, and so on. The zero value is assign to the record with REVISION_NUM = 0 as a default.
     */
    public final TableField<DtRecord, Integer> REVISION_TRACKING_NUM = createField(DSL.name("revision_tracking_num"), org.jooq.impl.SQLDataType.INTEGER.nullable(false).defaultValue(org.jooq.impl.DSL.inline("0", org.jooq.impl.SQLDataType.INTEGER)), this, "REVISION_TRACKING_NUM supports the ability to undo changes during a revision (life cycle of a revision is from the component's EDITING state to PUBLISHED state). Once the component has transitioned into the PUBLISHED state for its particular revision, all revision tracking records are deleted except the latest one. REVISION_TRACKING_NUM can be 0, 1, 2, and so on. The zero value is assign to the record with REVISION_NUM = 0 as a default.");

    /**
     * The column <code>oagi.dt.revision_action</code>. This indicates the action associated with the record. The action can be 1 = INSERT, 2 = UPDATE, and 3 = DELETE. This column is null for the current record.
     */
    public final TableField<DtRecord, Byte> REVISION_ACTION = createField(DSL.name("revision_action"), org.jooq.impl.SQLDataType.TINYINT.defaultValue(org.jooq.impl.DSL.inline("1", org.jooq.impl.SQLDataType.TINYINT)), this, "This indicates the action associated with the record. The action can be 1 = INSERT, 2 = UPDATE, and 3 = DELETE. This column is null for the current record.");

    /**
     * The column <code>oagi.dt.release_id</code>. RELEASE_ID is an incremental integer. It is an unformatted counter part of the RELEASE_NUMBER in the RELEASE table. RELEASE_ID can be 1, 2, 3, and so on. A release ID indicates the release point when a particular component revision is released. A component revision is only released once and assumed to be included in the subsequent releases unless it has been deleted (as indicated by the REVISION_ACTION column).

Not all component revisions have an associated RELEASE_ID because some revisions may never be released. USER_EXTENSION_GROUP component type is never part of a release.

Unpublished components cannot be released.

This column is NULL for the current record.
     */
    public final TableField<DtRecord, ULong> RELEASE_ID = createField(DSL.name("release_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "RELEASE_ID is an incremental integer. It is an unformatted counter part of the RELEASE_NUMBER in the RELEASE table. RELEASE_ID can be 1, 2, 3, and so on. A release ID indicates the release point when a particular component revision is released. A component revision is only released once and assumed to be included in the subsequent releases unless it has been deleted (as indicated by the REVISION_ACTION column).\n\nNot all component revisions have an associated RELEASE_ID because some revisions may never be released. USER_EXTENSION_GROUP component type is never part of a release.\n\nUnpublished components cannot be released.\n\nThis column is NULL for the current record.");

    /**
     * The column <code>oagi.dt.current_bdt_id</code>. This is a self-foreign-key. It points from a revised record to the current record. The current record is denoted by the record whose REVISION_NUM is 0. Revised records (a.k.a. history records) and their current record must have the same GUID.

It is noted that although this is a foreign key by definition, we don't specify a foreign key in the data model. This is because when an entity is deleted the current record won't exist anymore.

The value of this column for the current record should be left NULL.

The column name is specific to BDT because, the column does not apply to CDT.
     */
    public final TableField<DtRecord, ULong> CURRENT_BDT_ID = createField(DSL.name("current_bdt_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "This is a self-foreign-key. It points from a revised record to the current record. The current record is denoted by the record whose REVISION_NUM is 0. Revised records (a.k.a. history records) and their current record must have the same GUID.\n\nIt is noted that although this is a foreign key by definition, we don't specify a foreign key in the data model. This is because when an entity is deleted the current record won't exist anymore.\n\nThe value of this column for the current record should be left NULL.\n\nThe column name is specific to BDT because, the column does not apply to CDT.");

    /**
     * The column <code>oagi.dt.is_deprecated</code>. Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be created).
     */
    public final TableField<DtRecord, Byte> IS_DEPRECATED = createField(DSL.name("is_deprecated"), org.jooq.impl.SQLDataType.TINYINT.nullable(false).defaultValue(org.jooq.impl.DSL.inline("0", org.jooq.impl.SQLDataType.TINYINT)), this, "Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be created).");

    /**
     * Create a <code>oagi.dt</code> table reference
     */
    public Dt() {
        this(DSL.name("dt"), null);
    }

    /**
     * Create an aliased <code>oagi.dt</code> table reference
     */
    public Dt(String alias) {
        this(DSL.name(alias), DT);
    }

    /**
     * Create an aliased <code>oagi.dt</code> table reference
     */
    public Dt(Name alias) {
        this(alias, DT);
    }

    private Dt(Name alias, Table<DtRecord> aliased) {
        this(alias, aliased, null);
    }

    private Dt(Name alias, Table<DtRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("The DT table stores both CDT and BDT. The two types of DTs are differentiated by the TYPE column."));
    }

    public <O extends Record> Dt(Table<O> child, ForeignKey<O, DtRecord> key) {
        super(child, key, DT);
    }

    @Override
    public Schema getSchema() {
        return Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.DT_DT_BASED_DT_ID_FK, Indexes.DT_DT_CREATED_BY_FK, Indexes.DT_DT_CURRENT_BDT_ID_FK, Indexes.DT_DT_LAST_UPDATED_BY_FK, Indexes.DT_DT_MODULE_ID_FK, Indexes.DT_DT_OWNER_USER_ID_FK, Indexes.DT_DT_PREVIOUS_VERSION_DT_ID_FK, Indexes.DT_DT_RELEASE_ID_FK, Indexes.DT_DT_UK1, Indexes.DT_PRIMARY);
    }

    @Override
    public Identity<DtRecord, ULong> getIdentity() {
        return Keys.IDENTITY_DT;
    }

    @Override
    public UniqueKey<DtRecord> getPrimaryKey() {
        return Keys.KEY_DT_PRIMARY;
    }

    @Override
    public List<UniqueKey<DtRecord>> getKeys() {
        return Arrays.<UniqueKey<DtRecord>>asList(Keys.KEY_DT_PRIMARY, Keys.KEY_DT_DT_UK1);
    }

    @Override
    public List<ForeignKey<DtRecord, ?>> getReferences() {
        return Arrays.<ForeignKey<DtRecord, ?>>asList(Keys.DT_PREVIOUS_VERSION_DT_ID_FK, Keys.DT_BASED_DT_ID_FK, Keys.DT_MODULE_ID_FK, Keys.DT_CREATED_BY_FK, Keys.DT_LAST_UPDATED_BY_FK, Keys.DT_OWNER_USER_ID_FK, Keys.DT_RELEASE_ID_FK, Keys.DT_CURRENT_BDT_ID_FK);
    }

    public org.oagi.srt.entity.jooq.tables.Dt dtPreviousVersionDtIdFk() {
        return new org.oagi.srt.entity.jooq.tables.Dt(this, Keys.DT_PREVIOUS_VERSION_DT_ID_FK);
    }

    public org.oagi.srt.entity.jooq.tables.Dt dtBasedDtIdFk() {
        return new org.oagi.srt.entity.jooq.tables.Dt(this, Keys.DT_BASED_DT_ID_FK);
    }

    public Module module() {
        return new Module(this, Keys.DT_MODULE_ID_FK);
    }

    public AppUser dtCreatedByFk() {
        return new AppUser(this, Keys.DT_CREATED_BY_FK);
    }

    public AppUser dtLastUpdatedByFk() {
        return new AppUser(this, Keys.DT_LAST_UPDATED_BY_FK);
    }

    public AppUser dtOwnerUserIdFk() {
        return new AppUser(this, Keys.DT_OWNER_USER_ID_FK);
    }

    public Release release() {
        return new Release(this, Keys.DT_RELEASE_ID_FK);
    }

    public org.oagi.srt.entity.jooq.tables.Dt dtCurrentBdtIdFk() {
        return new org.oagi.srt.entity.jooq.tables.Dt(this, Keys.DT_CURRENT_BDT_ID_FK);
    }

    @Override
    public Dt as(String alias) {
        return new Dt(DSL.name(alias), this);
    }

    @Override
    public Dt as(Name alias) {
        return new Dt(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public Dt rename(String name) {
        return new Dt(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Dt rename(Name name) {
        return new Dt(name, null);
    }
}