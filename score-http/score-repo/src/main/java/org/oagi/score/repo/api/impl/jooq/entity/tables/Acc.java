/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.InverseForeignKey;
import org.jooq.Name;
import org.jooq.Path;
import org.jooq.PlainSQL;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.SQL;
import org.jooq.Schema;
import org.jooq.Select;
import org.jooq.Stringly;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.Indexes;
import org.oagi.score.repo.api.impl.jooq.entity.Keys;
import org.oagi.score.repo.api.impl.jooq.entity.Oagi;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Acc.AccPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AccManifest.AccManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AppUser.AppUserPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Ascc.AsccPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Asccp.AsccpPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Bcc.BccPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.BieUserExtRevision.BieUserExtRevisionPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Namespace.NamespacePath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.AccRecord;


/**
 * The ACC table holds information about complex data structured concepts. For
 * example, OAGIS's Components, Nouns, and BODs are captured in the ACC table.
 * 
 * Note that only Extension is supported when deriving ACC from another ACC. (So
 * if there is a restriction needed, maybe that concept should placed higher in
 * the derivation hierarchy rather than lower.)
 * 
 * In OAGIS, all XSD extensions will be treated as a qualification of an ACC.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Acc extends TableImpl<AccRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.acc</code>
     */
    public static final Acc ACC = new Acc();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<AccRecord> getRecordType() {
        return AccRecord.class;
    }

    /**
     * The column <code>oagi.acc.acc_id</code>. A internal, primary database key
     * of an ACC.
     */
    public final TableField<AccRecord, ULong> ACC_ID = createField(DSL.name("acc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "A internal, primary database key of an ACC.");

    /**
     * The column <code>oagi.acc.guid</code>. A globally unique identifier
     * (GUID).
     */
    public final TableField<AccRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.CHAR(32).nullable(false), this, "A globally unique identifier (GUID).");

    /**
     * The column <code>oagi.acc.type</code>. The Type of the ACC. List:
     * Default, Extension, AllExtension.
     */
    public final TableField<AccRecord, String> TYPE = createField(DSL.name("type"), SQLDataType.VARCHAR(32).defaultValue(DSL.field(DSL.raw("'Default'"), SQLDataType.VARCHAR)), this, "The Type of the ACC. List: Default, Extension, AllExtension.");

    /**
     * The column <code>oagi.acc.object_class_term</code>. Object class name of
     * the ACC concept. For OAGIS, this is generally name of a type with the
     * "Type" truncated from the end. Per CCS the name is space separated. "ID"
     * is expanded to "Identifier".
     */
    public final TableField<AccRecord, String> OBJECT_CLASS_TERM = createField(DSL.name("object_class_term"), SQLDataType.VARCHAR(100).nullable(false), this, "Object class name of the ACC concept. For OAGIS, this is generally name of a type with the \"Type\" truncated from the end. Per CCS the name is space separated. \"ID\" is expanded to \"Identifier\".");

    /**
     * The column <code>oagi.acc.definition</code>. This is a documentation or
     * description of the ACC. Since ACC is business context independent, this
     * is a business context independent description of the ACC concept.
     */
    public final TableField<AccRecord, String> DEFINITION = createField(DSL.name("definition"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "This is a documentation or description of the ACC. Since ACC is business context independent, this is a business context independent description of the ACC concept.");

    /**
     * The column <code>oagi.acc.definition_source</code>. This is typically a
     * URL identifying the source of the DEFINITION column.
     */
    public final TableField<AccRecord, String> DEFINITION_SOURCE = createField(DSL.name("definition_source"), SQLDataType.VARCHAR(100).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This is typically a URL identifying the source of the DEFINITION column.");

    /**
     * The column <code>oagi.acc.based_acc_id</code>. BASED_ACC_ID is a foreign
     * key to the ACC table itself. It represents the ACC that is qualified by
     * this ACC. In general CCS sense, a qualification can be a content
     * extension or restriction, but the current scope supports only extension.
     */
    public final TableField<AccRecord, ULong> BASED_ACC_ID = createField(DSL.name("based_acc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "BASED_ACC_ID is a foreign key to the ACC table itself. It represents the ACC that is qualified by this ACC. In general CCS sense, a qualification can be a content extension or restriction, but the current scope supports only extension.");

    /**
     * The column <code>oagi.acc.object_class_qualifier</code>. This column
     * stores the qualifier of an ACC, particularly when it has a based ACC. 
     */
    public final TableField<AccRecord, String> OBJECT_CLASS_QUALIFIER = createField(DSL.name("object_class_qualifier"), SQLDataType.VARCHAR(100).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This column stores the qualifier of an ACC, particularly when it has a based ACC. ");

    /**
     * The column <code>oagi.acc.oagis_component_type</code>. The value can be 0
     * = BASE, 1 = SEMANTICS, 2 = EXTENSION, 3 = SEMANTIC_GROUP, 4 =
     * USER_EXTENSION_GROUP, 5 = EMBEDDED. Generally, BASE is assigned when the
     * OBJECT_CLASS_TERM contains "Base" at the end. EXTENSION is assigned with
     * the OBJECT_CLASS_TERM contains "Extension" at the end. SEMANTIC_GROUP is
     * assigned when an ACC is imported from an XSD Group. USER_EXTENSION_GROUP
     * is a wrapper ACC (a virtual ACC) for segregating user's extension
     * content. EMBEDDED is used for an ACC whose content is not explicitly
     * defined in the database, for example, the Any Structured Content ACC that
     * corresponds to the xsd:any.  Other cases are assigned SEMANTICS. 
     */
    public final TableField<AccRecord, Integer> OAGIS_COMPONENT_TYPE = createField(DSL.name("oagis_component_type"), SQLDataType.INTEGER.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.INTEGER)), this, "The value can be 0 = BASE, 1 = SEMANTICS, 2 = EXTENSION, 3 = SEMANTIC_GROUP, 4 = USER_EXTENSION_GROUP, 5 = EMBEDDED. Generally, BASE is assigned when the OBJECT_CLASS_TERM contains \"Base\" at the end. EXTENSION is assigned with the OBJECT_CLASS_TERM contains \"Extension\" at the end. SEMANTIC_GROUP is assigned when an ACC is imported from an XSD Group. USER_EXTENSION_GROUP is a wrapper ACC (a virtual ACC) for segregating user's extension content. EMBEDDED is used for an ACC whose content is not explicitly defined in the database, for example, the Any Structured Content ACC that corresponds to the xsd:any.  Other cases are assigned SEMANTICS. ");

    /**
     * The column <code>oagi.acc.namespace_id</code>. Foreign key to the
     * NAMESPACE table. This is the namespace to which the entity belongs. This
     * namespace column is primarily used in the case the component is a user's
     * component because there is also a namespace assigned at the release
     * level.
     */
    public final TableField<AccRecord, ULong> NAMESPACE_ID = createField(DSL.name("namespace_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "Foreign key to the NAMESPACE table. This is the namespace to which the entity belongs. This namespace column is primarily used in the case the component is a user's component because there is also a namespace assigned at the release level.");

    /**
     * The column <code>oagi.acc.created_by</code>. Foreign key to the APP_USER
     * table referring to the user who creates the entity.\n\nThis column never
     * change between the history and the current record for a given revision.
     * The history record should have the same value as that of its current
     * record.
     */
    public final TableField<AccRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the user who creates the entity.\\n\\nThis column never change between the history and the current record for a given revision. The history record should have the same value as that of its current record.");

    /**
     * The column <code>oagi.acc.owner_user_id</code>. Foreign key to the
     * APP_USER table. This is the user who owns the entity, is allowed to edit
     * the entity, and who can transfer the ownership to another user.\n\nThe
     * ownership can change throughout the history, but undoing shouldn't
     * rollback the ownership. 
     */
    public final TableField<AccRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. This is the user who owns the entity, is allowed to edit the entity, and who can transfer the ownership to another user.\\n\\nThe ownership can change throughout the history, but undoing shouldn't rollback the ownership. ");

    /**
     * The column <code>oagi.acc.last_updated_by</code>. Foreign key to the
     * APP_USER table referring to the last user who updated the record. \n\nIn
     * the history record, this should always be the user who is editing the
     * entity (perhaps except when the ownership has just been changed).
     */
    public final TableField<AccRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who updated the record. \\n\\nIn the history record, this should always be the user who is editing the entity (perhaps except when the ownership has just been changed).");

    /**
     * The column <code>oagi.acc.creation_timestamp</code>. Timestamp when the
     * revision of the ACC was created. \n\nThis never change for a revision.
     */
    public final TableField<AccRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "Timestamp when the revision of the ACC was created. \\n\\nThis never change for a revision.");

    /**
     * The column <code>oagi.acc.last_update_timestamp</code>. The timestamp
     * when the record was last updated.\n\nThe value of this column in the
     * latest history record should be the same as that of the current record.
     * This column keeps the record of when the revision has occurred.
     */
    public final TableField<AccRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.\\n\\nThe value of this column in the latest history record should be the same as that of the current record. This column keeps the record of when the revision has occurred.");

    /**
     * The column <code>oagi.acc.state</code>. Deleted, WIP, Draft, QA,
     * Candidate, Production, Release Draft, Published. This the revision life
     * cycle state of the ACC.
     * 
     * State change can't be undone. But the history record can still keep the
     * records of when the state was changed.
     */
    public final TableField<AccRecord, String> STATE = createField(DSL.name("state"), SQLDataType.VARCHAR(20).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "Deleted, WIP, Draft, QA, Candidate, Production, Release Draft, Published. This the revision life cycle state of the ACC.\n\nState change can't be undone. But the history record can still keep the records of when the state was changed.");

    /**
     * The column <code>oagi.acc.is_deprecated</code>. Indicates whether the CC
     * is deprecated and should not be reused (i.e., no new reference to this
     * record should be allowed).
     */
    public final TableField<AccRecord, Byte> IS_DEPRECATED = createField(DSL.name("is_deprecated"), SQLDataType.TINYINT.defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "Indicates whether the CC is deprecated and should not be reused (i.e., no new reference to this record should be allowed).");

    /**
     * The column <code>oagi.acc.replacement_acc_id</code>. This refers to a
     * replacement if the record is deprecated.
     */
    public final TableField<AccRecord, ULong> REPLACEMENT_ACC_ID = createField(DSL.name("replacement_acc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "This refers to a replacement if the record is deprecated.");

    /**
     * The column <code>oagi.acc.is_abstract</code>. This is the XML Schema
     * abstract flag. Default is false. If it is true, the abstract flag will be
     * set to true when generating a corresponding xsd:complexType. So although
     * this flag may not apply to some ACCs such as those that are xsd:group. It
     * is still have a false value.
     */
    public final TableField<AccRecord, Byte> IS_ABSTRACT = createField(DSL.name("is_abstract"), SQLDataType.TINYINT.defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "This is the XML Schema abstract flag. Default is false. If it is true, the abstract flag will be set to true when generating a corresponding xsd:complexType. So although this flag may not apply to some ACCs such as those that are xsd:group. It is still have a false value.");

    /**
     * The column <code>oagi.acc.prev_acc_id</code>. A self-foreign key to
     * indicate the previous history record.
     */
    public final TableField<AccRecord, ULong> PREV_ACC_ID = createField(DSL.name("prev_acc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "A self-foreign key to indicate the previous history record.");

    /**
     * The column <code>oagi.acc.next_acc_id</code>. A self-foreign key to
     * indicate the next history record.
     */
    public final TableField<AccRecord, ULong> NEXT_ACC_ID = createField(DSL.name("next_acc_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "A self-foreign key to indicate the next history record.");

    private Acc(Name alias, Table<AccRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private Acc(Name alias, Table<AccRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment("The ACC table holds information about complex data structured concepts. For example, OAGIS's Components, Nouns, and BODs are captured in the ACC table.\n\nNote that only Extension is supported when deriving ACC from another ACC. (So if there is a restriction needed, maybe that concept should placed higher in the derivation hierarchy rather than lower.)\n\nIn OAGIS, all XSD extensions will be treated as a qualification of an ACC."), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>oagi.acc</code> table reference
     */
    public Acc(String alias) {
        this(DSL.name(alias), ACC);
    }

    /**
     * Create an aliased <code>oagi.acc</code> table reference
     */
    public Acc(Name alias) {
        this(alias, ACC);
    }

    /**
     * Create a <code>oagi.acc</code> table reference
     */
    public Acc() {
        this(DSL.name("acc"), null);
    }

    public <O extends Record> Acc(Table<O> path, ForeignKey<O, AccRecord> childPath, InverseForeignKey<O, AccRecord> parentPath) {
        super(path, childPath, parentPath, ACC);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class AccPath extends Acc implements Path<AccRecord> {
        public <O extends Record> AccPath(Table<O> path, ForeignKey<O, AccRecord> childPath, InverseForeignKey<O, AccRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private AccPath(Name alias, Table<AccRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public AccPath as(String alias) {
            return new AccPath(DSL.name(alias), this);
        }

        @Override
        public AccPath as(Name alias) {
            return new AccPath(alias, this);
        }

        @Override
        public AccPath as(Table<?> alias) {
            return new AccPath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.ACC_ACC_GUID_IDX, Indexes.ACC_ACC_LAST_UPDATE_TIMESTAMP_DESC_IDX);
    }

    @Override
    public Identity<AccRecord, ULong> getIdentity() {
        return (Identity<AccRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<AccRecord> getPrimaryKey() {
        return Keys.KEY_ACC_PRIMARY;
    }

    @Override
    public List<ForeignKey<AccRecord, ?>> getReferences() {
        return Arrays.asList(Keys.ACC_BASED_ACC_ID_FK, Keys.ACC_NAMESPACE_ID_FK, Keys.ACC_CREATED_BY_FK, Keys.ACC_OWNER_USER_ID_FK, Keys.ACC_LAST_UPDATED_BY_FK, Keys.ACC_REPLACEMENT_ACC_ID_FK, Keys.ACC_PREV_ACC_ID_FK, Keys.ACC_NEXT_ACC_ID_FK);
    }

    private transient AccPath _accBasedAccIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table, via the
     * <code>acc_based_acc_id_fk</code> key.
     */
    public AccPath accBasedAccIdFk() {
        if (_accBasedAccIdFk == null)
            _accBasedAccIdFk = new AccPath(this, Keys.ACC_BASED_ACC_ID_FK, null);

        return _accBasedAccIdFk;
    }

    private transient NamespacePath _namespace;

    /**
     * Get the implicit join path to the <code>oagi.namespace</code> table.
     */
    public NamespacePath namespace() {
        if (_namespace == null)
            _namespace = new NamespacePath(this, Keys.ACC_NAMESPACE_ID_FK, null);

        return _namespace;
    }

    private transient AppUserPath _accCreatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>acc_created_by_fk</code> key.
     */
    public AppUserPath accCreatedByFk() {
        if (_accCreatedByFk == null)
            _accCreatedByFk = new AppUserPath(this, Keys.ACC_CREATED_BY_FK, null);

        return _accCreatedByFk;
    }

    private transient AppUserPath _accOwnerUserIdFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>acc_owner_user_id_fk</code> key.
     */
    public AppUserPath accOwnerUserIdFk() {
        if (_accOwnerUserIdFk == null)
            _accOwnerUserIdFk = new AppUserPath(this, Keys.ACC_OWNER_USER_ID_FK, null);

        return _accOwnerUserIdFk;
    }

    private transient AppUserPath _accLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>acc_last_updated_by_fk</code> key.
     */
    public AppUserPath accLastUpdatedByFk() {
        if (_accLastUpdatedByFk == null)
            _accLastUpdatedByFk = new AppUserPath(this, Keys.ACC_LAST_UPDATED_BY_FK, null);

        return _accLastUpdatedByFk;
    }

    private transient AccPath _accReplacementAccIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table, via the
     * <code>acc_replacement_acc_id_fk</code> key.
     */
    public AccPath accReplacementAccIdFk() {
        if (_accReplacementAccIdFk == null)
            _accReplacementAccIdFk = new AccPath(this, Keys.ACC_REPLACEMENT_ACC_ID_FK, null);

        return _accReplacementAccIdFk;
    }

    private transient AccPath _accPrevAccIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table, via the
     * <code>acc_prev_acc_id_fk</code> key.
     */
    public AccPath accPrevAccIdFk() {
        if (_accPrevAccIdFk == null)
            _accPrevAccIdFk = new AccPath(this, Keys.ACC_PREV_ACC_ID_FK, null);

        return _accPrevAccIdFk;
    }

    private transient AccPath _accNextAccIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table, via the
     * <code>acc_next_acc_id_fk</code> key.
     */
    public AccPath accNextAccIdFk() {
        if (_accNextAccIdFk == null)
            _accNextAccIdFk = new AccPath(this, Keys.ACC_NEXT_ACC_ID_FK, null);

        return _accNextAccIdFk;
    }

    private transient AccManifestPath _accManifest;

    /**
     * Get the implicit to-many join path to the <code>oagi.acc_manifest</code>
     * table
     */
    public AccManifestPath accManifest() {
        if (_accManifest == null)
            _accManifest = new AccManifestPath(this, null, Keys.ACC_MANIFEST_ACC_ID_FK.getInverseKey());

        return _accManifest;
    }

    private transient AsccpPath _asccp;

    /**
     * Get the implicit to-many join path to the <code>oagi.asccp</code> table
     */
    public AsccpPath asccp() {
        if (_asccp == null)
            _asccp = new AsccpPath(this, null, Keys.ASCCP_ROLE_OF_ACC_ID_FK.getInverseKey());

        return _asccp;
    }

    private transient AsccPath _ascc;

    /**
     * Get the implicit to-many join path to the <code>oagi.ascc</code> table
     */
    public AsccPath ascc() {
        if (_ascc == null)
            _ascc = new AsccPath(this, null, Keys.ASCC_FROM_ACC_ID_FK.getInverseKey());

        return _ascc;
    }

    private transient BccPath _bcc;

    /**
     * Get the implicit to-many join path to the <code>oagi.bcc</code> table
     */
    public BccPath bcc() {
        if (_bcc == null)
            _bcc = new BccPath(this, null, Keys.BCC_FROM_ACC_ID_FK.getInverseKey());

        return _bcc;
    }

    private transient BieUserExtRevisionPath _bieUserExtRevisionExtAccIdFk;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.bie_user_ext_revision</code> table, via the
     * <code>bie_user_ext_revision_ext_acc_id_fk</code> key
     */
    public BieUserExtRevisionPath bieUserExtRevisionExtAccIdFk() {
        if (_bieUserExtRevisionExtAccIdFk == null)
            _bieUserExtRevisionExtAccIdFk = new BieUserExtRevisionPath(this, null, Keys.BIE_USER_EXT_REVISION_EXT_ACC_ID_FK.getInverseKey());

        return _bieUserExtRevisionExtAccIdFk;
    }

    private transient BieUserExtRevisionPath _bieUserExtRevisionUserExtAccIdFk;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.bie_user_ext_revision</code> table, via the
     * <code>bie_user_ext_revision_user_ext_acc_id_fk</code> key
     */
    public BieUserExtRevisionPath bieUserExtRevisionUserExtAccIdFk() {
        if (_bieUserExtRevisionUserExtAccIdFk == null)
            _bieUserExtRevisionUserExtAccIdFk = new BieUserExtRevisionPath(this, null, Keys.BIE_USER_EXT_REVISION_USER_EXT_ACC_ID_FK.getInverseKey());

        return _bieUserExtRevisionUserExtAccIdFk;
    }

    @Override
    public Acc as(String alias) {
        return new Acc(DSL.name(alias), this);
    }

    @Override
    public Acc as(Name alias) {
        return new Acc(alias, this);
    }

    @Override
    public Acc as(Table<?> alias) {
        return new Acc(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Acc rename(String name) {
        return new Acc(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Acc rename(Name name) {
        return new Acc(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Acc rename(Table<?> name) {
        return new Acc(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc where(Condition condition) {
        return new Acc(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Acc where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Acc where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Acc where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Acc where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Acc whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
