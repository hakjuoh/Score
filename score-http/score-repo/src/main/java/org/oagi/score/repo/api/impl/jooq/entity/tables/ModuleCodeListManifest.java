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
import org.oagi.score.repo.api.impl.jooq.entity.Keys;
import org.oagi.score.repo.api.impl.jooq.entity.Oagi;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.ModuleCodeListManifestRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ModuleCodeListManifest extends TableImpl<ModuleCodeListManifestRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.module_code_list_manifest</code>
     */
    public static final ModuleCodeListManifest MODULE_CODE_LIST_MANIFEST = new ModuleCodeListManifest();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ModuleCodeListManifestRecord> getRecordType() {
        return ModuleCodeListManifestRecord.class;
    }

    /**
     * The column
     * <code>oagi.module_code_list_manifest.module_code_list_manifest_id</code>.
     * Primary key.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> MODULE_CODE_LIST_MANIFEST_ID = createField(DSL.name("module_code_list_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Primary key.");

    /**
     * The column
     * <code>oagi.module_code_list_manifest.module_set_release_id</code>. A
     * foreign key of the module set release record.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> MODULE_SET_RELEASE_ID = createField(DSL.name("module_set_release_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key of the module set release record.");

    /**
     * The column
     * <code>oagi.module_code_list_manifest.code_list_manifest_id</code>. A
     * foreign key of the code list manifest record.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> CODE_LIST_MANIFEST_ID = createField(DSL.name("code_list_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key of the code list manifest record.");

    /**
     * The column <code>oagi.module_code_list_manifest.module_id</code>. This
     * indicates a module.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> MODULE_ID = createField(DSL.name("module_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "This indicates a module.");

    /**
     * The column <code>oagi.module_code_list_manifest.created_by</code>.
     * Foreign key to the APP_USER table. It indicates the user who created this
     * record.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It indicates the user who created this record.");

    /**
     * The column <code>oagi.module_code_list_manifest.last_updated_by</code>.
     * Foreign key to the APP_USER table referring to the last user who updated
     * the record.
     */
    public final TableField<ModuleCodeListManifestRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who updated the record.");

    /**
     * The column
     * <code>oagi.module_code_list_manifest.creation_timestamp</code>. The
     * timestamp when the record was first created.
     */
    public final TableField<ModuleCodeListManifestRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was first created.");

    /**
     * The column
     * <code>oagi.module_code_list_manifest.last_update_timestamp</code>. The
     * timestamp when the record was last updated.
     */
    public final TableField<ModuleCodeListManifestRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.");

    private ModuleCodeListManifest(Name alias, Table<ModuleCodeListManifestRecord> aliased) {
        this(alias, aliased, null);
    }

    private ModuleCodeListManifest(Name alias, Table<ModuleCodeListManifestRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.module_code_list_manifest</code> table
     * reference
     */
    public ModuleCodeListManifest(String alias) {
        this(DSL.name(alias), MODULE_CODE_LIST_MANIFEST);
    }

    /**
     * Create an aliased <code>oagi.module_code_list_manifest</code> table
     * reference
     */
    public ModuleCodeListManifest(Name alias) {
        this(alias, MODULE_CODE_LIST_MANIFEST);
    }

    /**
     * Create a <code>oagi.module_code_list_manifest</code> table reference
     */
    public ModuleCodeListManifest() {
        this(DSL.name("module_code_list_manifest"), null);
    }

    public <O extends Record> ModuleCodeListManifest(Table<O> child, ForeignKey<O, ModuleCodeListManifestRecord> key) {
        super(child, key, MODULE_CODE_LIST_MANIFEST);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<ModuleCodeListManifestRecord, ULong> getIdentity() {
        return (Identity<ModuleCodeListManifestRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<ModuleCodeListManifestRecord> getPrimaryKey() {
        return Keys.KEY_MODULE_CODE_LIST_MANIFEST_PRIMARY;
    }

    @Override
    public List<ForeignKey<ModuleCodeListManifestRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MODULE_CODE_LIST_MANIFEST_MODULE_SET_RELEASE_ID_FK, Keys.MODULE_CODE_LIST_MANIFEST_CODE_LIST_MANIFEST_ID_FK, Keys.MODULE_CODE_LIST_MANIFEST_MODULE_ID_FK, Keys.MODULE_CODE_LIST_MANIFEST_CREATED_BY_FK, Keys.MODULE_CODE_LIST_MANIFEST_LAST_UPDATED_BY_FK);
    }

    private transient ModuleSetRelease _moduleSetRelease;
    private transient CodeListManifest _codeListManifest;
    private transient Module _module;
    private transient AppUser _moduleCodeListManifestCreatedByFk;
    private transient AppUser _moduleCodeListManifestLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.module_set_release</code>
     * table.
     */
    public ModuleSetRelease moduleSetRelease() {
        if (_moduleSetRelease == null)
            _moduleSetRelease = new ModuleSetRelease(this, Keys.MODULE_CODE_LIST_MANIFEST_MODULE_SET_RELEASE_ID_FK);

        return _moduleSetRelease;
    }

    /**
     * Get the implicit join path to the <code>oagi.code_list_manifest</code>
     * table.
     */
    public CodeListManifest codeListManifest() {
        if (_codeListManifest == null)
            _codeListManifest = new CodeListManifest(this, Keys.MODULE_CODE_LIST_MANIFEST_CODE_LIST_MANIFEST_ID_FK);

        return _codeListManifest;
    }

    /**
     * Get the implicit join path to the <code>oagi.module</code> table.
     */
    public Module module() {
        if (_module == null)
            _module = new Module(this, Keys.MODULE_CODE_LIST_MANIFEST_MODULE_ID_FK);

        return _module;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_code_list_manifest_created_by_fk</code> key.
     */
    public AppUser moduleCodeListManifestCreatedByFk() {
        if (_moduleCodeListManifestCreatedByFk == null)
            _moduleCodeListManifestCreatedByFk = new AppUser(this, Keys.MODULE_CODE_LIST_MANIFEST_CREATED_BY_FK);

        return _moduleCodeListManifestCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_code_list_manifest_last_updated_by_fk</code> key.
     */
    public AppUser moduleCodeListManifestLastUpdatedByFk() {
        if (_moduleCodeListManifestLastUpdatedByFk == null)
            _moduleCodeListManifestLastUpdatedByFk = new AppUser(this, Keys.MODULE_CODE_LIST_MANIFEST_LAST_UPDATED_BY_FK);

        return _moduleCodeListManifestLastUpdatedByFk;
    }

    @Override
    public ModuleCodeListManifest as(String alias) {
        return new ModuleCodeListManifest(DSL.name(alias), this);
    }

    @Override
    public ModuleCodeListManifest as(Name alias) {
        return new ModuleCodeListManifest(alias, this);
    }

    @Override
    public ModuleCodeListManifest as(Table<?> alias) {
        return new ModuleCodeListManifest(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleCodeListManifest rename(String name) {
        return new ModuleCodeListManifest(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleCodeListManifest rename(Name name) {
        return new ModuleCodeListManifest(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleCodeListManifest rename(Table<?> name) {
        return new ModuleCodeListManifest(name.getQualifiedName(), null);
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
