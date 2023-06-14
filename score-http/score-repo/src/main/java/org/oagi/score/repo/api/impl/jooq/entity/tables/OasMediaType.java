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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.OasMediaTypeRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class OasMediaType extends TableImpl<OasMediaTypeRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oas_media_type</code>
     */
    public static final OasMediaType OAS_MEDIA_TYPE = new OasMediaType();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<OasMediaTypeRecord> getRecordType() {
        return OasMediaTypeRecord.class;
    }

    /**
     * The column <code>oagi.oas_media_type.oas_media_type_id</code>. The
     * primary key of the record.
     */
    public final TableField<OasMediaTypeRecord, ULong> OAS_MEDIA_TYPE_ID = createField(DSL.name("oas_media_type_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_media_type.guid</code>. The GUID of the record.
     */
    public final TableField<OasMediaTypeRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.VARCHAR(41).nullable(false), this, "The GUID of the record.");

    /**
     * The column <code>oagi.oas_media_type.description</code>. On POST, PUT,
     * and PATCH, $ref is present
     */
    public final TableField<OasMediaTypeRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB, this, "On POST, PUT, and PATCH, $ref is present");

    /**
     * The column <code>oagi.oas_media_type.owner_user_id</code>. The user who
     * owns the record.
     */
    public final TableField<OasMediaTypeRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who owns the record.");

    /**
     * The column <code>oagi.oas_media_type.created_by</code>. The user who
     * creates the record.
     */
    public final TableField<OasMediaTypeRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who creates the record.");

    /**
     * The column <code>oagi.oas_media_type.last_updated_by</code>. The user who
     * last updates the record.
     */
    public final TableField<OasMediaTypeRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who last updates the record.");

    /**
     * The column <code>oagi.oas_media_type.creation_timestamp</code>. The
     * timestamp when the record is created.
     */
    public final TableField<OasMediaTypeRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is created.");

    /**
     * The column <code>oagi.oas_media_type.last_update_timestamp</code>. The
     * timestamp when the record is last updated.
     */
    public final TableField<OasMediaTypeRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is last updated.");

    private OasMediaType(Name alias, Table<OasMediaTypeRecord> aliased) {
        this(alias, aliased, null);
    }

    private OasMediaType(Name alias, Table<OasMediaTypeRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oas_media_type</code> table reference
     */
    public OasMediaType(String alias) {
        this(DSL.name(alias), OAS_MEDIA_TYPE);
    }

    /**
     * Create an aliased <code>oagi.oas_media_type</code> table reference
     */
    public OasMediaType(Name alias) {
        this(alias, OAS_MEDIA_TYPE);
    }

    /**
     * Create a <code>oagi.oas_media_type</code> table reference
     */
    public OasMediaType() {
        this(DSL.name("oas_media_type"), null);
    }

    public <O extends Record> OasMediaType(Table<O> child, ForeignKey<O, OasMediaTypeRecord> key) {
        super(child, key, OAS_MEDIA_TYPE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<OasMediaTypeRecord, ULong> getIdentity() {
        return (Identity<OasMediaTypeRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<OasMediaTypeRecord> getPrimaryKey() {
        return Keys.KEY_OAS_MEDIA_TYPE_PRIMARY;
    }

    @Override
    public List<ForeignKey<OasMediaTypeRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAS_MEDIA_TYPE_OWNER_USER_ID_FK, Keys.OAS_MEDIA_TYPE_CREATED_BY_FK, Keys.OAS_MEDIA_TYPE_LAST_UPDATED_BY_FK);
    }

    private transient AppUser _oasMediaTypeOwnerUserIdFk;
    private transient AppUser _oasMediaTypeCreatedByFk;
    private transient AppUser _oasMediaTypeLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_media_type_owner_user_id_fk</code> key.
     */
    public AppUser oasMediaTypeOwnerUserIdFk() {
        if (_oasMediaTypeOwnerUserIdFk == null)
            _oasMediaTypeOwnerUserIdFk = new AppUser(this, Keys.OAS_MEDIA_TYPE_OWNER_USER_ID_FK);

        return _oasMediaTypeOwnerUserIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_media_type_created_by_fk</code> key.
     */
    public AppUser oasMediaTypeCreatedByFk() {
        if (_oasMediaTypeCreatedByFk == null)
            _oasMediaTypeCreatedByFk = new AppUser(this, Keys.OAS_MEDIA_TYPE_CREATED_BY_FK);

        return _oasMediaTypeCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_media_type_last_updated_by_fk</code> key.
     */
    public AppUser oasMediaTypeLastUpdatedByFk() {
        if (_oasMediaTypeLastUpdatedByFk == null)
            _oasMediaTypeLastUpdatedByFk = new AppUser(this, Keys.OAS_MEDIA_TYPE_LAST_UPDATED_BY_FK);

        return _oasMediaTypeLastUpdatedByFk;
    }

    @Override
    public OasMediaType as(String alias) {
        return new OasMediaType(DSL.name(alias), this);
    }

    @Override
    public OasMediaType as(Name alias) {
        return new OasMediaType(alias, this);
    }

    @Override
    public OasMediaType as(Table<?> alias) {
        return new OasMediaType(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public OasMediaType rename(String name) {
        return new OasMediaType(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasMediaType rename(Name name) {
        return new OasMediaType(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasMediaType rename(Table<?> name) {
        return new OasMediaType(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row8 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row8<ULong, String, String, ULong, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row8) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function8<? super ULong, ? super String, ? super String, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function8<? super ULong, ? super String, ? super String, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
