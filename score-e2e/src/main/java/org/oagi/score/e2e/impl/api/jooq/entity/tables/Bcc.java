/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.e2e.impl.api.jooq.entity.tables;


import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.types.ULong;
import org.oagi.score.e2e.impl.api.jooq.entity.Indexes;
import org.oagi.score.e2e.impl.api.jooq.entity.Keys;
import org.oagi.score.e2e.impl.api.jooq.entity.Oagi;
import org.oagi.score.e2e.impl.api.jooq.entity.tables.records.BccRecord;


/**
 * A BCC represents a relationship/association between an ACC and a BCCP. It
 * creates a data element for an ACC. 
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Bcc extends TableImpl<BccRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.bcc</code>
     */
    public static final Bcc BCC = new Bcc();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<BccRecord> getRecordType() {
        return BccRecord.class;
    }

    /**
     * The column <code>oagi.bcc.bcc_id</code>. A internal, primary database key
     * of an BCC.
     */
    public final TableField<BccRecord, ULong> BCC_ID = createField(DSL.name("bcc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "A internal, primary database key of an BCC.");

    /**
     * The column <code>oagi.bcc.guid</code>. A globally unique identifier
     * (GUID).
     */
    public final TableField<BccRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.CHAR(32).nullable(false), this, "A globally unique identifier (GUID).");

    /**
     * The column <code>oagi.bcc.cardinality_min</code>. Minimum cardinality of
     * the TO_BCCP_ID. The valid values are non-negative integer.
     */
    public final TableField<BccRecord, Integer> CARDINALITY_MIN = createField(DSL.name("cardinality_min"), SQLDataType.INTEGER.nullable(false), this, "Minimum cardinality of the TO_BCCP_ID. The valid values are non-negative integer.");

    /**
     * The column <code>oagi.bcc.cardinality_max</code>. Maximum cardinality of
     * the TO_BCCP_ID. The valid values are integer -1 and up. Specifically, -1
     * means unbounded. 0 means prohibited or not to use.',
     */
    public final TableField<BccRecord, Integer> CARDINALITY_MAX = createField(DSL.name("cardinality_max"), SQLDataType.INTEGER.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.INTEGER)), this, "Maximum cardinality of the TO_BCCP_ID. The valid values are integer -1 and up. Specifically, -1 means unbounded. 0 means prohibited or not to use.',");

    /**
     * The column <code>oagi.bcc.to_bccp_id</code>. TO_BCCP_ID is a foreign key
     * to an BCCP table record. It is basically pointing to a child data element
     * of the FROM_ACC_ID. 
     * 
     * Note that for the BCC history records, this column always points to the
     * BCCP_ID of the current record of a BCCP.',
     */
    public final TableField<BccRecord, ULong> TO_BCCP_ID = createField(DSL.name("to_bccp_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "TO_BCCP_ID is a foreign key to an BCCP table record. It is basically pointing to a child data element of the FROM_ACC_ID. \n\nNote that for the BCC history records, this column always points to the BCCP_ID of the current record of a BCCP.',");

    /**
     * The column <code>oagi.bcc.from_acc_id</code>. FROM_ACC_ID is a foreign
     * key pointing to an ACC record. It is basically pointing to a parent data
     * element (type) of the TO_BCCP_ID. 
     * 
     * Note that for the BCC history records, this column always points to the
     * ACC_ID of the current record of an ACC.
     */
    public final TableField<BccRecord, ULong> FROM_ACC_ID = createField(DSL.name("from_acc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "FROM_ACC_ID is a foreign key pointing to an ACC record. It is basically pointing to a parent data element (type) of the TO_BCCP_ID. \n\nNote that for the BCC history records, this column always points to the ACC_ID of the current record of an ACC.");

    /**
     * The column <code>oagi.bcc.seq_key</code>. @deprecated since 2.0.0. This
     * indicates the order of the associations among other siblings. A valid
     * value is positive integer. The SEQ_KEY at the CC side is localized. In
     * other words, if an ACC is based on another ACC, SEQ_KEY of ASCCs or BCCs
     * of the former ACC starts at 1 again.
     */
    public final TableField<BccRecord, Integer> SEQ_KEY = createField(DSL.name("seq_key"), SQLDataType.INTEGER.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.INTEGER)), this, "@deprecated since 2.0.0. This indicates the order of the associations among other siblings. A valid value is positive integer. The SEQ_KEY at the CC side is localized. In other words, if an ACC is based on another ACC, SEQ_KEY of ASCCs or BCCs of the former ACC starts at 1 again.");

    /**
     * The column <code>oagi.bcc.entity_type</code>. This is a code list: 0 =
     * ATTRIBUTE and 1 = ELEMENT. An expression generator may or may not use
     * this information. This column is necessary because some of the BCCs are
     * xsd:attribute and some are xsd:element in the OAGIS 10.x. 
     */
    public final TableField<BccRecord, Integer> ENTITY_TYPE = createField(DSL.name("entity_type"), SQLDataType.INTEGER.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.INTEGER)), this, "This is a code list: 0 = ATTRIBUTE and 1 = ELEMENT. An expression generator may or may not use this information. This column is necessary because some of the BCCs are xsd:attribute and some are xsd:element in the OAGIS 10.x. ");

    /**
     * The column <code>oagi.bcc.den</code>. DEN (dictionary entry name) of the
     * BCC. This column can be derived from QUALIFIER and OBJECT_CLASS_TERM of
     * the FROM_ACC_ID and DEN of the TO_BCCP_ID as QUALIFIER + "_ " +
     * OBJECT_CLASS_TERM + ". " + DEN. 
     */
    public final TableField<BccRecord, String> DEN = createField(DSL.name("den"), SQLDataType.VARCHAR(200).nullable(false), this, "DEN (dictionary entry name) of the BCC. This column can be derived from QUALIFIER and OBJECT_CLASS_TERM of the FROM_ACC_ID and DEN of the TO_BCCP_ID as QUALIFIER + \"_ \" + OBJECT_CLASS_TERM + \". \" + DEN. ");

    /**
     * The column <code>oagi.bcc.definition</code>. This is a documentation or
     * description of the BCC. Since BCC is business context independent, this
     * is a business context independent description of the BCC. Since there are
     * definitions also in the BCCP (as referenced by TO_BCCP_ID column) and the
     * BDT under that BCCP, the definition in the BCC is a specific description
     * about the relationship between the ACC (as in FROM_ACC_ID) and the BCCP.
     */
    public final TableField<BccRecord, String> DEFINITION = createField(DSL.name("definition"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "This is a documentation or description of the BCC. Since BCC is business context independent, this is a business context independent description of the BCC. Since there are definitions also in the BCCP (as referenced by TO_BCCP_ID column) and the BDT under that BCCP, the definition in the BCC is a specific description about the relationship between the ACC (as in FROM_ACC_ID) and the BCCP.");

    /**
     * The column <code>oagi.bcc.definition_source</code>. This is typically a
     * URL identifying the source of the DEFINITION column.
     */
    public final TableField<BccRecord, String> DEFINITION_SOURCE = createField(DSL.name("definition_source"), SQLDataType.VARCHAR(100).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This is typically a URL identifying the source of the DEFINITION column.");

    /**
     * The column <code>oagi.bcc.created_by</code>. Foreign key to the APP_USER
     * table referring to the user who creates the entity.
     * 
     * This column never change between the history and the current record. The
     * history record should have the same value as that of its current record.
     */
    public final TableField<BccRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the user who creates the entity.\n\nThis column never change between the history and the current record. The history record should have the same value as that of its current record.");

    /**
     * The column <code>oagi.bcc.owner_user_id</code>. Foreign key to the
     * APP_USER table. This is the user who owns the entity, is allowed to edit
     * the entity, and who can transfer the ownership to another user.
     * 
     * The ownership can change throughout the history, but undoing shouldn't
     * rollback the ownership.
     */
    public final TableField<BccRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\n\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership.");

    /**
     * The column <code>oagi.bcc.last_updated_by</code>. Foreign key to the
     * APP_USER table referring to the last user who has updated the record. 
     * 
     * In the history record, this should always be the user who is editing the
     * entity (perhaps except when the ownership has just been changed).
     */
    public final TableField<BccRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who has updated the record. \n\nIn the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).");

    /**
     * The column <code>oagi.bcc.creation_timestamp</code>. Timestamp when the
     * revision of the BCC was created. 
     * 
     * This never change for a revision.
     */
    public final TableField<BccRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "Timestamp when the revision of the BCC was created. \n\nThis never change for a revision.");

    /**
     * The column <code>oagi.bcc.last_update_timestamp</code>. The timestamp
     * when the record was last updated.
     * 
     * The value of this column in the latest history record should be the same
     * as that of the current record. This column keeps the record of when the
     * change has occurred.
     */
    public final TableField<BccRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.\n\nThe value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the change has occurred.");

    /**
     * The column <code>oagi.bcc.state</code>. Deleted, WIP, Draft, QA,
     * Candidate, Production, Release Draft, Published. This the revision life
     * cycle state of the BCC.
     * 
     * State change can't be undone. But the history record can still keep the
     * records of when the state was changed.
     */
    public final TableField<BccRecord, String> STATE = createField(DSL.name("state"), SQLDataType.VARCHAR(20).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "Deleted, WIP, Draft, QA, Candidate, Production, Release Draft, Published. This the revision life cycle state of the BCC.\n\nState change can't be undone. But the history record can still keep the records of when the state was changed.");

    /**
     * The column <code>oagi.bcc.is_deprecated</code>. Indicates whether the CC
     * is deprecated and should not be reused (i.e., no new reference to this
     * record should be created).
     */
    public final TableField<BccRecord, Byte> IS_DEPRECATED = createField(DSL.name("is_deprecated"), SQLDataType.TINYINT.nullable(false), this, "Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be created).");

    /**
     * The column <code>oagi.bcc.replacement_bcc_id</code>. This refers to a
     * replacement if the record is deprecated.
     */
    public final TableField<BccRecord, ULong> REPLACEMENT_BCC_ID = createField(DSL.name("replacement_bcc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "This refers to a replacement if the record is deprecated.");

    /**
     * The column <code>oagi.bcc.is_nillable</code>. @deprecated since 2.0.0 in
     * favor of impossibility of nillable association (element reference) in XML
     * schema.
     * 
     * Indicate whether the field can have a NULL This is corresponding to the
     * nillable flag in the XML schema.
     */
    public final TableField<BccRecord, Byte> IS_NILLABLE = createField(DSL.name("is_nillable"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "@deprecated since 2.0.0 in favor of impossibility of nillable association (element reference) in XML schema.\n\nIndicate whether the field can have a NULL This is corresponding to the nillable flag in the XML schema.");

    /**
     * The column <code>oagi.bcc.default_value</code>. This set the default
     * value at the association level. 
     */
    public final TableField<BccRecord, String> DEFAULT_VALUE = createField(DSL.name("default_value"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "This set the default value at the association level. ");

    /**
     * The column <code>oagi.bcc.fixed_value</code>. This column captures the
     * fixed value constraint. Default and fixed value constraints cannot be
     * used at the same time.
     */
    public final TableField<BccRecord, String> FIXED_VALUE = createField(DSL.name("fixed_value"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "This column captures the fixed value constraint. Default and fixed value constraints cannot be used at the same time.");

    /**
     * The column <code>oagi.bcc.prev_bcc_id</code>. A self-foreign key to
     * indicate the previous history record.
     */
    public final TableField<BccRecord, ULong> PREV_BCC_ID = createField(DSL.name("prev_bcc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "A self-foreign key to indicate the previous history record.");

    /**
     * The column <code>oagi.bcc.next_bcc_id</code>. A self-foreign key to
     * indicate the next history record.
     */
    public final TableField<BccRecord, ULong> NEXT_BCC_ID = createField(DSL.name("next_bcc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "A self-foreign key to indicate the next history record.");

    private Bcc(Name alias, Table<BccRecord> aliased) {
        this(alias, aliased, null);
    }

    private Bcc(Name alias, Table<BccRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("A BCC represents a relationship/association between an ACC and a BCCP. It creates a data element for an ACC. "), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.bcc</code> table reference
     */
    public Bcc(String alias) {
        this(DSL.name(alias), BCC);
    }

    /**
     * Create an aliased <code>oagi.bcc</code> table reference
     */
    public Bcc(Name alias) {
        this(alias, BCC);
    }

    /**
     * Create a <code>oagi.bcc</code> table reference
     */
    public Bcc() {
        this(DSL.name("bcc"), null);
    }

    public <O extends Record> Bcc(Table<O> child, ForeignKey<O, BccRecord> key) {
        super(child, key, BCC);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.BCC_BCC_GUID_IDX, Indexes.BCC_BCC_LAST_UPDATE_TIMESTAMP_DESC_IDX);
    }

    @Override
    public Identity<BccRecord, ULong> getIdentity() {
        return (Identity<BccRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<BccRecord> getPrimaryKey() {
        return Keys.KEY_BCC_PRIMARY;
    }

    @Override
    public List<ForeignKey<BccRecord, ?>> getReferences() {
        return Arrays.asList(Keys.BCC_TO_BCCP_ID_FK, Keys.BCC_FROM_ACC_ID_FK, Keys.BCC_CREATED_BY_FK, Keys.BCC_OWNER_USER_ID_FK, Keys.BCC_LAST_UPDATED_BY_FK, Keys.BCC_REPLACEMENT_BCC_ID_FK, Keys.BCC_PREV_BCC_ID_FK, Keys.BCC_NEXT_BCC_ID_FK);
    }

    private transient Bccp _bccp;
    private transient Acc _acc;
    private transient AppUser _bccCreatedByFk;
    private transient AppUser _bccOwnerUserIdFk;
    private transient AppUser _bccLastUpdatedByFk;
    private transient Bcc _bccReplacementBccIdFk;
    private transient Bcc _bccPrevBccIdFk;
    private transient Bcc _bccNextBccIdFk;

    /**
     * Get the implicit join path to the <code>oagi.bccp</code> table.
     */
    public Bccp bccp() {
        if (_bccp == null)
            _bccp = new Bccp(this, Keys.BCC_TO_BCCP_ID_FK);

        return _bccp;
    }

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table.
     */
    public Acc acc() {
        if (_acc == null)
            _acc = new Acc(this, Keys.BCC_FROM_ACC_ID_FK);

        return _acc;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>bcc_created_by_fk</code> key.
     */
    public AppUser bccCreatedByFk() {
        if (_bccCreatedByFk == null)
            _bccCreatedByFk = new AppUser(this, Keys.BCC_CREATED_BY_FK);

        return _bccCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>bcc_owner_user_id_fk</code> key.
     */
    public AppUser bccOwnerUserIdFk() {
        if (_bccOwnerUserIdFk == null)
            _bccOwnerUserIdFk = new AppUser(this, Keys.BCC_OWNER_USER_ID_FK);

        return _bccOwnerUserIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>bcc_last_updated_by_fk</code> key.
     */
    public AppUser bccLastUpdatedByFk() {
        if (_bccLastUpdatedByFk == null)
            _bccLastUpdatedByFk = new AppUser(this, Keys.BCC_LAST_UPDATED_BY_FK);

        return _bccLastUpdatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.bcc</code> table, via the
     * <code>bcc_replacement_bcc_id_fk</code> key.
     */
    public Bcc bccReplacementBccIdFk() {
        if (_bccReplacementBccIdFk == null)
            _bccReplacementBccIdFk = new Bcc(this, Keys.BCC_REPLACEMENT_BCC_ID_FK);

        return _bccReplacementBccIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.bcc</code> table, via the
     * <code>bcc_prev_bcc_id_fk</code> key.
     */
    public Bcc bccPrevBccIdFk() {
        if (_bccPrevBccIdFk == null)
            _bccPrevBccIdFk = new Bcc(this, Keys.BCC_PREV_BCC_ID_FK);

        return _bccPrevBccIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.bcc</code> table, via the
     * <code>bcc_next_bcc_id_fk</code> key.
     */
    public Bcc bccNextBccIdFk() {
        if (_bccNextBccIdFk == null)
            _bccNextBccIdFk = new Bcc(this, Keys.BCC_NEXT_BCC_ID_FK);

        return _bccNextBccIdFk;
    }

    @Override
    public Bcc as(String alias) {
        return new Bcc(DSL.name(alias), this);
    }

    @Override
    public Bcc as(Name alias) {
        return new Bcc(alias, this);
    }

    @Override
    public Bcc as(Table<?> alias) {
        return new Bcc(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Bcc rename(String name) {
        return new Bcc(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Bcc rename(Name name) {
        return new Bcc(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Bcc rename(Table<?> name) {
        return new Bcc(name.getQualifiedName(), null);
    }
}
