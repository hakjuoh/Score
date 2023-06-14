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
import org.jooq.Function10;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row10;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.OasParameterLinkRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class OasParameterLink extends TableImpl<OasParameterLinkRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oas_parameter_link</code>
     */
    public static final OasParameterLink OAS_PARAMETER_LINK = new OasParameterLink();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<OasParameterLinkRecord> getRecordType() {
        return OasParameterLinkRecord.class;
    }

    /**
     * The column <code>oagi.oas_parameter_link.oas_parameter_link_id</code>.
     * The primary key of the record.
     */
    public final TableField<OasParameterLinkRecord, ULong> OAS_PARAMETER_LINK_ID = createField(DSL.name("oas_parameter_link_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_parameter_link.oas_response_id</code>.
     */
    public final TableField<OasParameterLinkRecord, ULong> OAS_RESPONSE_ID = createField(DSL.name("oas_response_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.oas_parameter_link.oas_parameter_id</code>.
     */
    public final TableField<OasParameterLinkRecord, ULong> OAS_PARAMETER_ID = createField(DSL.name("oas_parameter_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.oas_parameter_link.oas_operation_id</code>.
     */
    public final TableField<OasParameterLinkRecord, ULong> OAS_OPERATION_ID = createField(DSL.name("oas_operation_id"), SQLDataType.BIGINTUNSIGNED, this, "");

    /**
     * The column <code>oagi.oas_parameter_link.expression</code>. jsonPathSnip
     * for example '$response.body#/purchaseOrderHeader.identifier'
     */
    public final TableField<OasParameterLinkRecord, String> EXPRESSION = createField(DSL.name("expression"), SQLDataType.CLOB, this, "jsonPathSnip for example '$response.body#/purchaseOrderHeader.identifier'");

    /**
     * The column <code>oagi.oas_parameter_link.description</code>.
     */
    public final TableField<OasParameterLinkRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB, this, "");

    /**
     * The column <code>oagi.oas_parameter_link.created_by</code>. The user who
     * creates the record.
     */
    public final TableField<OasParameterLinkRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who creates the record.");

    /**
     * The column <code>oagi.oas_parameter_link.last_updated_by</code>. The user
     * who last updates the record.
     */
    public final TableField<OasParameterLinkRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who last updates the record.");

    /**
     * The column <code>oagi.oas_parameter_link.creation_timestamp</code>. The
     * timestamp when the record is created.
     */
    public final TableField<OasParameterLinkRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is created.");

    /**
     * The column <code>oagi.oas_parameter_link.last_update_timestamp</code>.
     * The timestamp when the record is last updated.
     */
    public final TableField<OasParameterLinkRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is last updated.");

    private OasParameterLink(Name alias, Table<OasParameterLinkRecord> aliased) {
        this(alias, aliased, null);
    }

    private OasParameterLink(Name alias, Table<OasParameterLinkRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oas_parameter_link</code> table reference
     */
    public OasParameterLink(String alias) {
        this(DSL.name(alias), OAS_PARAMETER_LINK);
    }

    /**
     * Create an aliased <code>oagi.oas_parameter_link</code> table reference
     */
    public OasParameterLink(Name alias) {
        this(alias, OAS_PARAMETER_LINK);
    }

    /**
     * Create a <code>oagi.oas_parameter_link</code> table reference
     */
    public OasParameterLink() {
        this(DSL.name("oas_parameter_link"), null);
    }

    public <O extends Record> OasParameterLink(Table<O> child, ForeignKey<O, OasParameterLinkRecord> key) {
        super(child, key, OAS_PARAMETER_LINK);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<OasParameterLinkRecord, ULong> getIdentity() {
        return (Identity<OasParameterLinkRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<OasParameterLinkRecord> getPrimaryKey() {
        return Keys.KEY_OAS_PARAMETER_LINK_PRIMARY;
    }

    @Override
    public List<ForeignKey<OasParameterLinkRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAS_PARAMETER_LINK_OAS_RESPONSE_ID_FK, Keys.OAS_PARAMETER_LINK_OAS_PARAMETER_ID_FK, Keys.OAS_PARAMETER_LINK_OAS_OPERATION_ID_FK, Keys.OAS_PARAMETER_LINK_CREATED_BY_FK, Keys.OAS_PARAMETER_LINK_LAST_UPDATED_BY_FK);
    }

    private transient OasResponse _oasResponse;
    private transient OasParameter _oasParameter;
    private transient OasOperation _oasOperation;
    private transient AppUser _oasParameterLinkCreatedByFk;
    private transient AppUser _oasParameterLinkLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.oas_response</code> table.
     */
    public OasResponse oasResponse() {
        if (_oasResponse == null)
            _oasResponse = new OasResponse(this, Keys.OAS_PARAMETER_LINK_OAS_RESPONSE_ID_FK);

        return _oasResponse;
    }

    /**
     * Get the implicit join path to the <code>oagi.oas_parameter</code> table.
     */
    public OasParameter oasParameter() {
        if (_oasParameter == null)
            _oasParameter = new OasParameter(this, Keys.OAS_PARAMETER_LINK_OAS_PARAMETER_ID_FK);

        return _oasParameter;
    }

    /**
     * Get the implicit join path to the <code>oagi.oas_operation</code> table.
     */
    public OasOperation oasOperation() {
        if (_oasOperation == null)
            _oasOperation = new OasOperation(this, Keys.OAS_PARAMETER_LINK_OAS_OPERATION_ID_FK);

        return _oasOperation;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_parameter_link_created_by_fk</code> key.
     */
    public AppUser oasParameterLinkCreatedByFk() {
        if (_oasParameterLinkCreatedByFk == null)
            _oasParameterLinkCreatedByFk = new AppUser(this, Keys.OAS_PARAMETER_LINK_CREATED_BY_FK);

        return _oasParameterLinkCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_parameter_link_last_updated_by_fk</code> key.
     */
    public AppUser oasParameterLinkLastUpdatedByFk() {
        if (_oasParameterLinkLastUpdatedByFk == null)
            _oasParameterLinkLastUpdatedByFk = new AppUser(this, Keys.OAS_PARAMETER_LINK_LAST_UPDATED_BY_FK);

        return _oasParameterLinkLastUpdatedByFk;
    }

    @Override
    public OasParameterLink as(String alias) {
        return new OasParameterLink(DSL.name(alias), this);
    }

    @Override
    public OasParameterLink as(Name alias) {
        return new OasParameterLink(alias, this);
    }

    @Override
    public OasParameterLink as(Table<?> alias) {
        return new OasParameterLink(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public OasParameterLink rename(String name) {
        return new OasParameterLink(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasParameterLink rename(Name name) {
        return new OasParameterLink(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasParameterLink rename(Table<?> name) {
        return new OasParameterLink(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row10 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row10<ULong, ULong, ULong, ULong, String, String, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row10) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function10<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super String, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function10<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super String, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
