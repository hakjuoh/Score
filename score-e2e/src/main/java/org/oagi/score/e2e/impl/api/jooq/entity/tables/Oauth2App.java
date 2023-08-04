/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.e2e.impl.api.jooq.entity.tables;


import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function19;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row19;
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
import org.oagi.score.e2e.impl.api.jooq.entity.Keys;
import org.oagi.score.e2e.impl.api.jooq.entity.Oagi;
import org.oagi.score.e2e.impl.api.jooq.entity.tables.records.Oauth2AppRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Oauth2App extends TableImpl<Oauth2AppRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oauth2_app</code>
     */
    public static final Oauth2App OAUTH2_APP = new Oauth2App();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<Oauth2AppRecord> getRecordType() {
        return Oauth2AppRecord.class;
    }

    /**
     * The column <code>oagi.oauth2_app.oauth2_app_id</code>.
     */
    public final TableField<Oauth2AppRecord, ULong> OAUTH2_APP_ID = createField(DSL.name("oauth2_app_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.oauth2_app.provider_name</code>.
     */
    public final TableField<Oauth2AppRecord, String> PROVIDER_NAME = createField(DSL.name("provider_name"), SQLDataType.VARCHAR(100).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.issuer_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> ISSUER_URI = createField(DSL.name("issuer_uri"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.authorization_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> AUTHORIZATION_URI = createField(DSL.name("authorization_uri"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.token_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> TOKEN_URI = createField(DSL.name("token_uri"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.user_info_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> USER_INFO_URI = createField(DSL.name("user_info_uri"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.jwk_set_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> JWK_SET_URI = createField(DSL.name("jwk_set_uri"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.redirect_uri</code>.
     */
    public final TableField<Oauth2AppRecord, String> REDIRECT_URI = createField(DSL.name("redirect_uri"), SQLDataType.VARCHAR(200).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.end_session_endpoint</code>.
     */
    public final TableField<Oauth2AppRecord, String> END_SESSION_ENDPOINT = createField(DSL.name("end_session_endpoint"), SQLDataType.VARCHAR(200).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.client_id</code>.
     */
    public final TableField<Oauth2AppRecord, String> CLIENT_ID = createField(DSL.name("client_id"), SQLDataType.VARCHAR(200).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.client_secret</code>.
     */
    public final TableField<Oauth2AppRecord, String> CLIENT_SECRET = createField(DSL.name("client_secret"), SQLDataType.VARCHAR(200).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.client_authentication_method</code>.
     */
    public final TableField<Oauth2AppRecord, String> CLIENT_AUTHENTICATION_METHOD = createField(DSL.name("client_authentication_method"), SQLDataType.VARCHAR(50).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.authorization_grant_type</code>.
     */
    public final TableField<Oauth2AppRecord, String> AUTHORIZATION_GRANT_TYPE = createField(DSL.name("authorization_grant_type"), SQLDataType.VARCHAR(50).nullable(false), this, "");

    /**
     * The column <code>oagi.oauth2_app.prompt</code>.
     */
    public final TableField<Oauth2AppRecord, String> PROMPT = createField(DSL.name("prompt"), SQLDataType.VARCHAR(20).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.display_provider_name</code>.
     */
    public final TableField<Oauth2AppRecord, String> DISPLAY_PROVIDER_NAME = createField(DSL.name("display_provider_name"), SQLDataType.VARCHAR(100).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.background_color</code>.
     */
    public final TableField<Oauth2AppRecord, String> BACKGROUND_COLOR = createField(DSL.name("background_color"), SQLDataType.VARCHAR(50).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.font_color</code>.
     */
    public final TableField<Oauth2AppRecord, String> FONT_COLOR = createField(DSL.name("font_color"), SQLDataType.VARCHAR(50).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "");

    /**
     * The column <code>oagi.oauth2_app.display_order</code>.
     */
    public final TableField<Oauth2AppRecord, Integer> DISPLAY_ORDER = createField(DSL.name("display_order"), SQLDataType.INTEGER.defaultValue(DSL.field(DSL.raw("0"), SQLDataType.INTEGER)), this, "");

    /**
     * The column <code>oagi.oauth2_app.is_disabled</code>.
     */
    public final TableField<Oauth2AppRecord, Byte> IS_DISABLED = createField(DSL.name("is_disabled"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "");

    private Oauth2App(Name alias, Table<Oauth2AppRecord> aliased) {
        this(alias, aliased, null);
    }

    private Oauth2App(Name alias, Table<Oauth2AppRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oauth2_app</code> table reference
     */
    public Oauth2App(String alias) {
        this(DSL.name(alias), OAUTH2_APP);
    }

    /**
     * Create an aliased <code>oagi.oauth2_app</code> table reference
     */
    public Oauth2App(Name alias) {
        this(alias, OAUTH2_APP);
    }

    /**
     * Create a <code>oagi.oauth2_app</code> table reference
     */
    public Oauth2App() {
        this(DSL.name("oauth2_app"), null);
    }

    public <O extends Record> Oauth2App(Table<O> child, ForeignKey<O, Oauth2AppRecord> key) {
        super(child, key, OAUTH2_APP);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<Oauth2AppRecord, ULong> getIdentity() {
        return (Identity<Oauth2AppRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<Oauth2AppRecord> getPrimaryKey() {
        return Keys.KEY_OAUTH2_APP_PRIMARY;
    }

    @Override
    public Oauth2App as(String alias) {
        return new Oauth2App(DSL.name(alias), this);
    }

    @Override
    public Oauth2App as(Name alias) {
        return new Oauth2App(alias, this);
    }

    @Override
    public Oauth2App as(Table<?> alias) {
        return new Oauth2App(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2App rename(String name) {
        return new Oauth2App(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2App rename(Name name) {
        return new Oauth2App(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Oauth2App rename(Table<?> name) {
        return new Oauth2App(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row19 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row19<ULong, String, String, String, String, String, String, String, String, String, String, String, String, String, String, String, String, Integer, Byte> fieldsRow() {
        return (Row19) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function19<? super ULong, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super Integer, ? super Byte, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function19<? super ULong, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super Integer, ? super Byte, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
