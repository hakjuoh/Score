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
import org.oagi.score.repo.api.impl.jooq.entity.tables.AppUser.AppUserPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Module.ModulePath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.ModuleSetRelease.ModuleSetReleasePath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.ModuleSetRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ModuleSet extends TableImpl<ModuleSetRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.module_set</code>
     */
    public static final ModuleSet MODULE_SET = new ModuleSet();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<ModuleSetRecord> getRecordType() {
        return ModuleSetRecord.class;
    }

    /**
     * The column <code>oagi.module_set.module_set_id</code>. Primary key.
     */
    public final TableField<ModuleSetRecord, ULong> MODULE_SET_ID = createField(DSL.name("module_set_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Primary key.");

    /**
     * The column <code>oagi.module_set.guid</code>. A globally unique
     * identifier (GUID).
     */
    public final TableField<ModuleSetRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.CHAR(32).nullable(false), this, "A globally unique identifier (GUID).");

    /**
     * The column <code>oagi.module_set.name</code>. This is the name of the
     * module set.
     */
    public final TableField<ModuleSetRecord, String> NAME = createField(DSL.name("name"), SQLDataType.VARCHAR(100).nullable(false), this, "This is the name of the module set.");

    /**
     * The column <code>oagi.module_set.description</code>. Description or
     * explanation about the module set or use of the module set.
     */
    public final TableField<ModuleSetRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "Description or explanation about the module set or use of the module set.");

    /**
     * The column <code>oagi.module_set.created_by</code>. Foreign key to the
     * APP_USER table. It indicates the user who created this MODULE_SET.
     */
    public final TableField<ModuleSetRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It indicates the user who created this MODULE_SET.");

    /**
     * The column <code>oagi.module_set.last_updated_by</code>. Foreign key to
     * the APP_USER table referring to the last user who updated the record.
     */
    public final TableField<ModuleSetRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table referring to the last user who updated the record.");

    /**
     * The column <code>oagi.module_set.creation_timestamp</code>. The timestamp
     * when the record was first created.
     */
    public final TableField<ModuleSetRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was first created.");

    /**
     * The column <code>oagi.module_set.last_update_timestamp</code>. The
     * timestamp when the record was last updated.
     */
    public final TableField<ModuleSetRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.");

    private ModuleSet(Name alias, Table<ModuleSetRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private ModuleSet(Name alias, Table<ModuleSetRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>oagi.module_set</code> table reference
     */
    public ModuleSet(String alias) {
        this(DSL.name(alias), MODULE_SET);
    }

    /**
     * Create an aliased <code>oagi.module_set</code> table reference
     */
    public ModuleSet(Name alias) {
        this(alias, MODULE_SET);
    }

    /**
     * Create a <code>oagi.module_set</code> table reference
     */
    public ModuleSet() {
        this(DSL.name("module_set"), null);
    }

    public <O extends Record> ModuleSet(Table<O> path, ForeignKey<O, ModuleSetRecord> childPath, InverseForeignKey<O, ModuleSetRecord> parentPath) {
        super(path, childPath, parentPath, MODULE_SET);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class ModuleSetPath extends ModuleSet implements Path<ModuleSetRecord> {
        public <O extends Record> ModuleSetPath(Table<O> path, ForeignKey<O, ModuleSetRecord> childPath, InverseForeignKey<O, ModuleSetRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private ModuleSetPath(Name alias, Table<ModuleSetRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public ModuleSetPath as(String alias) {
            return new ModuleSetPath(DSL.name(alias), this);
        }

        @Override
        public ModuleSetPath as(Name alias) {
            return new ModuleSetPath(alias, this);
        }

        @Override
        public ModuleSetPath as(Table<?> alias) {
            return new ModuleSetPath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<ModuleSetRecord, ULong> getIdentity() {
        return (Identity<ModuleSetRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<ModuleSetRecord> getPrimaryKey() {
        return Keys.KEY_MODULE_SET_PRIMARY;
    }

    @Override
    public List<ForeignKey<ModuleSetRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MODULE_SET_CREATED_BY_FK, Keys.MODULE_SET_LAST_UPDATED_BY_FK);
    }

    private transient AppUserPath _moduleSetCreatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_set_created_by_fk</code> key.
     */
    public AppUserPath moduleSetCreatedByFk() {
        if (_moduleSetCreatedByFk == null)
            _moduleSetCreatedByFk = new AppUserPath(this, Keys.MODULE_SET_CREATED_BY_FK, null);

        return _moduleSetCreatedByFk;
    }

    private transient AppUserPath _moduleSetLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>module_set_last_updated_by_fk</code> key.
     */
    public AppUserPath moduleSetLastUpdatedByFk() {
        if (_moduleSetLastUpdatedByFk == null)
            _moduleSetLastUpdatedByFk = new AppUserPath(this, Keys.MODULE_SET_LAST_UPDATED_BY_FK, null);

        return _moduleSetLastUpdatedByFk;
    }

    private transient ModulePath _module;

    /**
     * Get the implicit to-many join path to the <code>oagi.module</code> table
     */
    public ModulePath module() {
        if (_module == null)
            _module = new ModulePath(this, null, Keys.MODULE_MODULE_SET_ID_FK.getInverseKey());

        return _module;
    }

    private transient ModuleSetReleasePath _moduleSetRelease;

    /**
     * Get the implicit to-many join path to the
     * <code>oagi.module_set_release</code> table
     */
    public ModuleSetReleasePath moduleSetRelease() {
        if (_moduleSetRelease == null)
            _moduleSetRelease = new ModuleSetReleasePath(this, null, Keys.MODULE_SET_RELEASE_MODULE_SET_ID_FK.getInverseKey());

        return _moduleSetRelease;
    }

    @Override
    public ModuleSet as(String alias) {
        return new ModuleSet(DSL.name(alias), this);
    }

    @Override
    public ModuleSet as(Name alias) {
        return new ModuleSet(alias, this);
    }

    @Override
    public ModuleSet as(Table<?> alias) {
        return new ModuleSet(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleSet rename(String name) {
        return new ModuleSet(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleSet rename(Name name) {
        return new ModuleSet(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public ModuleSet rename(Table<?> name) {
        return new ModuleSet(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet where(Condition condition) {
        return new ModuleSet(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public ModuleSet where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public ModuleSet where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public ModuleSet where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public ModuleSet where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public ModuleSet whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
