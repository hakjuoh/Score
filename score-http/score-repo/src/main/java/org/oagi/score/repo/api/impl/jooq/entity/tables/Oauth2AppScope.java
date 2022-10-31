/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function3;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row3;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.Oauth2AppScopeRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Oauth2AppScope extends TableImpl<Oauth2AppScopeRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oauth2_app_scope</code>
     */
    public static final Oauth2AppScope OAUTH2_APP_SCOPE = new Oauth2AppScope();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<Oauth2AppScopeRecord> getRecordType() {
        return Oauth2AppScopeRecord.class;
    }

    /**
     * The column <code>oagi.oauth2_app_scope.oauth2_app_scope_id</code>.
     */
    public final TableField<Oauth2AppScopeRecord, ULong> OAUTH2_APP_SCOPE_ID = createField(DSL.name("oauth2_app_scope_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.oauth2_app_scope.oauth2_app_id</code>.
     */
    public final TableField<Oauth2AppScopeRecord, ULong> OAUTH2_APP_ID = createField(DSL.name("oauth2_app_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app_scope.scope</code>.
     */
    public final TableField<Oauth2AppScopeRecord, String> SCOPE = createField(DSL.name("scope"), SQLDataType.VARCHAR(100).nullable(false), this, "");

    private Oauth2AppScope(Name alias, Table<Oauth2AppScopeRecord> aliased) {
        this(alias, aliased, null);
    }

    private Oauth2AppScope(Name alias, Table<Oauth2AppScopeRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oauth2_app_scope</code> table reference
     */
    public Oauth2AppScope(String alias) {
        this(DSL.name(alias), OAUTH2_APP_SCOPE);
    }

    /**
     * Create an aliased <code>oagi.oauth2_app_scope</code> table reference
     */
    public Oauth2AppScope(Name alias) {
        this(alias, OAUTH2_APP_SCOPE);
    }

    /**
     * Create a <code>oagi.oauth2_app_scope</code> table reference
     */
    public Oauth2AppScope() {
        this(DSL.name("oauth2_app_scope"), null);
    }

    public <O extends Record> Oauth2AppScope(Table<O> child, ForeignKey<O, Oauth2AppScopeRecord> key) {
        super(child, key, OAUTH2_APP_SCOPE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<Oauth2AppScopeRecord, ULong> getIdentity() {
        return (Identity<Oauth2AppScopeRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<Oauth2AppScopeRecord> getPrimaryKey() {
        return Keys.KEY_OAUTH2_APP_SCOPE_PRIMARY;
    }

    @Override
    public List<ForeignKey<Oauth2AppScopeRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAUTH2_APP_SCOPE_OAUTH2_APP_ID_FK);
    }

    private transient Oauth2App _oauth2App;

    /**
     * Get the implicit join path to the <code>oagi.oauth2_app</code> table.
     */
    public Oauth2App oauth2App() {
        if (_oauth2App == null)
            _oauth2App = new Oauth2App(this, Keys.OAUTH2_APP_SCOPE_OAUTH2_APP_ID_FK);

        return _oauth2App;
    }

    @Override
    public Oauth2AppScope as(String alias) {
        return new Oauth2AppScope(DSL.name(alias), this);
    }

    @Override
    public Oauth2AppScope as(Name alias) {
        return new Oauth2AppScope(alias, this);
    }

    @Override
    public Oauth2AppScope as(Table<?> alias) {
        return new Oauth2AppScope(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2AppScope rename(String name) {
        return new Oauth2AppScope(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2AppScope rename(Name name) {
        return new Oauth2AppScope(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2AppScope rename(Table<?> name) {
        return new Oauth2AppScope(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row3 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row3<ULong, ULong, String> fieldsRow() {
        return (Row3) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function3<? super ULong, ? super ULong, ? super String, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function3<? super ULong, ? super ULong, ? super String, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
