/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
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
import org.oagi.score.repo.api.impl.jooq.entity.Keys;
import org.oagi.score.repo.api.impl.jooq.entity.Oagi;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Abie.AbiePath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Acc.AccPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AccManifest.AccManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AccManifestTag.AccManifestTagPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AsccManifest.AsccManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AsccpManifest.AsccpManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.BccManifest.BccManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Log.LogPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.ModuleAccManifest.ModuleAccManifestPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Release.ReleasePath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.SeqKey.SeqKeyPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Tag.TagPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.AccManifestRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AccManifest extends TableImpl<AccManifestRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.acc_manifest</code>
     */
    public static final AccManifest ACC_MANIFEST = new AccManifest();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<AccManifestRecord> getRecordType() {
        return AccManifestRecord.class;
    }

    /**
     * The column <code>oagi.acc_manifest.acc_manifest_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> ACC_MANIFEST_ID = createField(DSL.name("acc_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.acc_manifest.release_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> RELEASE_ID = createField(DSL.name("release_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.acc_manifest.acc_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> ACC_ID = createField(DSL.name("acc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.acc_manifest.based_acc_manifest_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> BASED_ACC_MANIFEST_ID = createField(DSL.name("based_acc_manifest_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "");

    /**
     * The column <code>oagi.acc_manifest.den</code>. DEN (dictionary entry
     * name) of the ACC. It can be derived as OBJECT_CLASS_QUALIFIER + "_ " +
     * OBJECT_CLASS_TERM + ". Details".
     */
    public final TableField<AccManifestRecord, String> DEN = createField(DSL.name("den"), SQLDataType.VARCHAR(200).nullable(false), this, "DEN (dictionary entry name) of the ACC. It can be derived as OBJECT_CLASS_QUALIFIER + \"_ \" + OBJECT_CLASS_TERM + \". Details\".");

    /**
     * The column <code>oagi.acc_manifest.conflict</code>. This indicates that
     * there is a conflict between self and relationship.
     */
    public final TableField<AccManifestRecord, Byte> CONFLICT = createField(DSL.name("conflict"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "This indicates that there is a conflict between self and relationship.");

    /**
     * The column <code>oagi.acc_manifest.log_id</code>. A foreign key pointed
     * to a log for the current record.
     */
    public final TableField<AccManifestRecord, ULong> LOG_ID = createField(DSL.name("log_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "A foreign key pointed to a log for the current record.");

    /**
     * The column <code>oagi.acc_manifest.replacement_acc_manifest_id</code>.
     * This refers to a replacement manifest if the record is deprecated.
     */
    public final TableField<AccManifestRecord, ULong> REPLACEMENT_ACC_MANIFEST_ID = createField(DSL.name("replacement_acc_manifest_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "This refers to a replacement manifest if the record is deprecated.");

    /**
     * The column <code>oagi.acc_manifest.prev_acc_manifest_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> PREV_ACC_MANIFEST_ID = createField(DSL.name("prev_acc_manifest_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "");

    /**
     * The column <code>oagi.acc_manifest.next_acc_manifest_id</code>.
     */
    public final TableField<AccManifestRecord, ULong> NEXT_ACC_MANIFEST_ID = createField(DSL.name("next_acc_manifest_id"), SQLDataType.BIGINTUNSIGNED.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.BIGINTUNSIGNED)), this, "");

    private AccManifest(Name alias, Table<AccManifestRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private AccManifest(Name alias, Table<AccManifestRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>oagi.acc_manifest</code> table reference
     */
    public AccManifest(String alias) {
        this(DSL.name(alias), ACC_MANIFEST);
    }

    /**
     * Create an aliased <code>oagi.acc_manifest</code> table reference
     */
    public AccManifest(Name alias) {
        this(alias, ACC_MANIFEST);
    }

    /**
     * Create a <code>oagi.acc_manifest</code> table reference
     */
    public AccManifest() {
        this(DSL.name("acc_manifest"), null);
    }

    public <O extends Record> AccManifest(Table<O> path, ForeignKey<O, AccManifestRecord> childPath, InverseForeignKey<O, AccManifestRecord> parentPath) {
        super(path, childPath, parentPath, ACC_MANIFEST);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class AccManifestPath extends AccManifest implements Path<AccManifestRecord> {
        public <O extends Record> AccManifestPath(Table<O> path, ForeignKey<O, AccManifestRecord> childPath, InverseForeignKey<O, AccManifestRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private AccManifestPath(Name alias, Table<AccManifestRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public AccManifestPath as(String alias) {
            return new AccManifestPath(DSL.name(alias), this);
        }

        @Override
        public AccManifestPath as(Name alias) {
            return new AccManifestPath(alias, this);
        }

        @Override
        public AccManifestPath as(Table<?> alias) {
            return new AccManifestPath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<AccManifestRecord, ULong> getIdentity() {
        return (Identity<AccManifestRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<AccManifestRecord> getPrimaryKey() {
        return Keys.KEY_ACC_MANIFEST_PRIMARY;
    }

    @Override
    public List<ForeignKey<AccManifestRecord, ?>> getReferences() {
        return Arrays.asList(Keys.ACC_MANIFEST_RELEASE_ID_FK, Keys.ACC_MANIFEST_ACC_ID_FK, Keys.ACC_MANIFEST_BASED_ACC_MANIFEST_ID_FK, Keys.ACC_MANIFEST_LOG_ID_FK, Keys.ACC_REPLACEMENT_ACC_MANIFEST_ID_FK, Keys.ACC_MANIFEST_PREV_ACC_MANIFEST_ID_FK, Keys.ACC_MANIFEST_NEXT_ACC_MANIFEST_ID_FK);
    }

    private transient ReleasePath _release;

    /**
     * Get the implicit join path to the <code>oagi.release</code> table.
     */
    public ReleasePath release() {
        if (_release == null)
            _release = new ReleasePath(this, Keys.ACC_MANIFEST_RELEASE_ID_FK, null);

        return _release;
    }

    private transient AccPath _acc;

    /**
     * Get the implicit join path to the <code>oagi.acc</code> table.
     */
    public AccPath acc() {
        if (_acc == null)
            _acc = new AccPath(this, Keys.ACC_MANIFEST_ACC_ID_FK, null);

        return _acc;
    }

    private transient AccManifestPath _accManifestBasedAccManifestIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc_manifest</code> table,
     * via the <code>acc_manifest_based_acc_manifest_id_fk</code> key.
     */
    public AccManifestPath accManifestBasedAccManifestIdFk() {
        if (_accManifestBasedAccManifestIdFk == null)
            _accManifestBasedAccManifestIdFk = new AccManifestPath(this, Keys.ACC_MANIFEST_BASED_ACC_MANIFEST_ID_FK, null);

        return _accManifestBasedAccManifestIdFk;
    }

    private transient LogPath _log;

    /**
     * Get the implicit join path to the <code>oagi.log</code> table.
     */
    public LogPath log() {
        if (_log == null)
            _log = new LogPath(this, Keys.ACC_MANIFEST_LOG_ID_FK, null);

        return _log;
    }

    private transient AccManifestPath _accReplacementAccManifestIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc_manifest</code> table,
     * via the <code>acc_replacement_acc_manifest_id_fk</code> key.
     */
    public AccManifestPath accReplacementAccManifestIdFk() {
        if (_accReplacementAccManifestIdFk == null)
            _accReplacementAccManifestIdFk = new AccManifestPath(this, Keys.ACC_REPLACEMENT_ACC_MANIFEST_ID_FK, null);

        return _accReplacementAccManifestIdFk;
    }

    private transient AccManifestPath _accManifestPrevAccManifestIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc_manifest</code> table,
     * via the <code>acc_manifest_prev_acc_manifest_id_fk</code> key.
     */
    public AccManifestPath accManifestPrevAccManifestIdFk() {
        if (_accManifestPrevAccManifestIdFk == null)
            _accManifestPrevAccManifestIdFk = new AccManifestPath(this, Keys.ACC_MANIFEST_PREV_ACC_MANIFEST_ID_FK, null);

        return _accManifestPrevAccManifestIdFk;
    }

    private transient AccManifestPath _accManifestNextAccManifestIdFk;

    /**
     * Get the implicit join path to the <code>oagi.acc_manifest</code> table,
     * via the <code>acc_manifest_next_acc_manifest_id_fk</code> key.
     */
    public AccManifestPath accManifestNextAccManifestIdFk() {
        if (_accManifestNextAccManifestIdFk == null)
            _accManifestNextAccManifestIdFk = new AccManifestPath(this, Keys.ACC_MANIFEST_NEXT_ACC_MANIFEST_ID_FK, null);

        return _accManifestNextAccManifestIdFk;
    }

    private transient AbiePath _abie;

    /**
     * Get the implicit to-many join path to the <code>oagi.abie</code> table
     */
    public AbiePath abie() {
        if (_abie == null)
            _abie = new AbiePath(this, null, Keys.ABIE_BASED_ACC_MANIFEST_ID_FK.getInverseKey());

        return _abie;
    }

    private transient AccManifestTagPath _accManifestTag;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.acc_manifest_tag</code> table
     */
    public AccManifestTagPath accManifestTag() {
        if (_accManifestTag == null)
            _accManifestTag = new AccManifestTagPath(this, null, Keys.ACC_MANIFEST_TAG_ACC_MANIFEST_ID_FK.getInverseKey());

        return _accManifestTag;
    }

    private transient AsccpManifestPath _asccpManifest;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.asccp_manifest</code> table
     */
    public AsccpManifestPath asccpManifest() {
        if (_asccpManifest == null)
            _asccpManifest = new AsccpManifestPath(this, null, Keys.ASCCP_MANIFEST_ROLE_OF_ACC_MANIFEST_ID_FK.getInverseKey());

        return _asccpManifest;
    }

    private transient AsccManifestPath _asccManifest;

    /**
     * Get the implicit to-many join path to the <code>oagi.ascc_manifest</code>
     * table
     */
    public AsccManifestPath asccManifest() {
        if (_asccManifest == null)
            _asccManifest = new AsccManifestPath(this, null, Keys.ASCC_MANIFEST_FROM_ACC_MANIFEST_ID_FK.getInverseKey());

        return _asccManifest;
    }

    private transient BccManifestPath _bccManifest;

    /**
     * Get the implicit to-many join path to the <code>oagi.bcc_manifest</code>
     * table
     */
    public BccManifestPath bccManifest() {
        if (_bccManifest == null)
            _bccManifest = new BccManifestPath(this, null, Keys.BCC_MANIFEST_FROM_ACC_MANIFEST_ID_FK.getInverseKey());

        return _bccManifest;
    }

    private transient ModuleAccManifestPath _moduleAccManifest;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.module_acc_manifest</code> table
     */
    public ModuleAccManifestPath moduleAccManifest() {
        if (_moduleAccManifest == null)
            _moduleAccManifest = new ModuleAccManifestPath(this, null, Keys.MODULE_ACC_MANIFEST_ACC_MANIFEST_ID_FK.getInverseKey());

        return _moduleAccManifest;
    }

    private transient SeqKeyPath _seqKey;

    /**
     * Get the implicit to-many join path to the <code>oagi.seq_key</code> table
     */
    public SeqKeyPath seqKey() {
        if (_seqKey == null)
            _seqKey = new SeqKeyPath(this, null, Keys.SEQ_KEY_FROM_ACC_MANIFEST_ID_FK.getInverseKey());

        return _seqKey;
    }

    /**
     * Get the implicit many-to-many join path to the <code>oagi.tag</code>
     * table
     */
    public TagPath tag() {
        return accManifestTag().tag();
    }

    @Override
    public AccManifest as(String alias) {
        return new AccManifest(DSL.name(alias), this);
    }

    @Override
    public AccManifest as(Name alias) {
        return new AccManifest(alias, this);
    }

    @Override
    public AccManifest as(Table<?> alias) {
        return new AccManifest(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public AccManifest rename(String name) {
        return new AccManifest(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public AccManifest rename(Name name) {
        return new AccManifest(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public AccManifest rename(Table<?> name) {
        return new AccManifest(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest where(Condition condition) {
        return new AccManifest(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public AccManifest where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public AccManifest where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public AccManifest where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public AccManifest where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public AccManifest whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
