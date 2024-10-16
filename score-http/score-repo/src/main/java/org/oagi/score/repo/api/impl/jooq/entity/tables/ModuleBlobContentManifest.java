/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function8;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row8;
import org.jooq.Schema;
import org.jooq.SelectField;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.ModuleBlobContentManifestRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ModuleBlobContentManifest extends TableImpl<ModuleBlobContentManifestRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.module_blob_content_manifest</code>
     */
    public static final ModuleBlobContentManifest MODULE_BLOB_CONTENT_MANIFEST = new ModuleBlobContentManifest();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ModuleBlobContentManifestRecord> getRecordType() {
        return ModuleBlobContentManifestRecord.class;
    }

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.module_blob_content_manifest_id</code>.
     * Primary key.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> MODULE_BLOB_CONTENT_MANIFEST_ID = createField(DSL.name("module_blob_content_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Primary key.");

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.module_set_release_id</code>. A
     * foreign key of the module set release record.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> MODULE_SET_RELEASE_ID = createField(DSL.name("module_set_release_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key of the module set release record.");

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.blob_content_manifest_id</code>.
     * A foreign key of the blob content manifest record.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> BLOB_CONTENT_MANIFEST_ID = createField(DSL.name("blob_content_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key of the blob content manifest record.");

    /**
     * The column <code>oagi.module_blob_content_manifest.module_id</code>. This
     * indicates a module.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> MODULE_ID = createField(DSL.name("module_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "This indicates a module.");

    /**
     * The column <code>oagi.module_blob_content_manifest.created_by</code>.
     * Foreign key to the APP_USER table. It indicates the user who created this
     * record.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It indicates the user who created this record.");

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.last_updated_by</code>. Foreign
     * key to the APP_USER table referring to the last user who updated the
     * record.
     */
    public final TableField<ModuleBlobContentManifestRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who updated the record.");

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.creation_timestamp</code>. The
     * timestamp when the record was first created.
     */
    public final TableField<ModuleBlobContentManifestRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was first created.");

    /**
     * The column
     * <code>oagi.module_blob_content_manifest.last_update_timestamp</code>. The
     * timestamp when the record was last updated.
     */
    public final TableField<ModuleBlobContentManifestRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.");

    private ModuleBlobContentManifest(Name alias, Table<ModuleBlobContentManifestRecord> aliased) {
        this(alias, aliased, null);
    }

    private ModuleBlobContentManifest(Name alias, Table<ModuleBlobContentManifestRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.module_blob_content_manifest</code> table
     * reference
     */
    public ModuleBlobContentManifest(String alias) {
        this(DSL.name(alias), MODULE_BLOB_CONTENT_MANIFEST);
    }

    /**
     * Create an aliased <code>oagi.module_blob_content_manifest</code> table
     * reference
     */
    public ModuleBlobContentManifest(Name alias) {
        this(alias, MODULE_BLOB_CONTENT_MANIFEST);
    }

    /**
     * Create a <code>oagi.module_blob_content_manifest</code> table reference
     */
    public ModuleBlobContentManifest() {
        this(DSL.name("module_blob_content_manifest"), null);
    }

    public <O extends Record> ModuleBlobContentManifest(Table<O> child, ForeignKey<O, ModuleBlobContentManifestRecord> key) {
        super(child, key, MODULE_BLOB_CONTENT_MANIFEST);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.MODULE_BLOB_CONTENT_MANIFEST_MMODULE_BLOB_CONTENT_MANIFEST_LAST_UPDATED_BY_FK, Indexes.MODULE_BLOB_CONTENT_MANIFEST_MODULE_BLOB_CONTENT_MANIFEST_BLOB_CONTENT_MANIFEST_ID_FK);
    }

    @Override
    public Identity<ModuleBlobContentManifestRecord, ULong> getIdentity() {
        return (Identity<ModuleBlobContentManifestRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<ModuleBlobContentManifestRecord> getPrimaryKey() {
        return Keys.KEY_MODULE_BLOB_CONTENT_MANIFEST_PRIMARY;
    }

    @Override
    public List<ForeignKey<ModuleBlobContentManifestRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MODULE_BLOB_CONTENT_MANIFEST_MODULE_SET_RELEASE_ID_FK, Keys.MODULE_BLOB_CONTENT_MANIFEST_ACC_MANIFEST_ID_FK, Keys.MODULE_BLOB_CONTENT_MANIFEST_MODULE_ID_FK, Keys.MODULE_BLOB_CONTENT_MANIFEST_CREATED_BY_FK, Keys.MODULE_BLOB_CONTENT_MANIFEST_LAST_UPDATED_BY_FK);
    }

    private transient ModuleSetRelease _moduleSetRelease;
    private transient BlobContentManifest _blobContentManifest;
    private transient Module _module;
    private transient AppUser _moduleBlobContentManifestCreatedByFk;
    private transient AppUser _moduleBlobContentManifestLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.module_set_release</code>
     * table.
     */
    public ModuleSetRelease moduleSetRelease() {
        if (_moduleSetRelease == null)
            _moduleSetRelease = new ModuleSetRelease(this, Keys.MODULE_BLOB_CONTENT_MANIFEST_MODULE_SET_RELEASE_ID_FK);

        return _moduleSetRelease;
    }

    /**
     * Get the implicit join path to the <code>oagi.blob_content_manifest</code>
     * table.
     */
    public BlobContentManifest blobContentManifest() {
        if (_blobContentManifest == null)
            _blobContentManifest = new BlobContentManifest(this, Keys.MODULE_BLOB_CONTENT_MANIFEST_ACC_MANIFEST_ID_FK);

        return _blobContentManifest;
    }

    /**
     * Get the implicit join path to the <code>oagi.module</code> table.
     */
    public Module module() {
        if (_module == null)
            _module = new Module(this, Keys.MODULE_BLOB_CONTENT_MANIFEST_MODULE_ID_FK);

        return _module;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_blob_content_manifest_created_by_fk</code> key.
     */
    public AppUser moduleBlobContentManifestCreatedByFk() {
        if (_moduleBlobContentManifestCreatedByFk == null)
            _moduleBlobContentManifestCreatedByFk = new AppUser(this, Keys.MODULE_BLOB_CONTENT_MANIFEST_CREATED_BY_FK);

        return _moduleBlobContentManifestCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_blob_content_manifest_last_updated_by_fk</code> key.
     */
    public AppUser moduleBlobContentManifestLastUpdatedByFk() {
        if (_moduleBlobContentManifestLastUpdatedByFk == null)
            _moduleBlobContentManifestLastUpdatedByFk = new AppUser(this, Keys.MODULE_BLOB_CONTENT_MANIFEST_LAST_UPDATED_BY_FK);

        return _moduleBlobContentManifestLastUpdatedByFk;
    }

    @Override
    public ModuleBlobContentManifest as(String alias) {
        return new ModuleBlobContentManifest(DSL.name(alias), this);
    }

    @Override
    public ModuleBlobContentManifest as(Name alias) {
        return new ModuleBlobContentManifest(alias, this);
    }

    @Override
    public ModuleBlobContentManifest as(Table<?> alias) {
        return new ModuleBlobContentManifest(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleBlobContentManifest rename(String name) {
        return new ModuleBlobContentManifest(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleBlobContentManifest rename(Name name) {
        return new ModuleBlobContentManifest(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleBlobContentManifest rename(Table<?> name) {
        return new ModuleBlobContentManifest(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row8 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row8<ULong, ULong, ULong, ULong, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row8) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function8<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function8<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
