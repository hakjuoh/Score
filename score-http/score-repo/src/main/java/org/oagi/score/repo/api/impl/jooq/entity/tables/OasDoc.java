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
import org.jooq.Function17;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row17;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.OasDocRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class OasDoc extends TableImpl<OasDocRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oas_doc</code>
     */
    public static final OasDoc OAS_DOC = new OasDoc();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<OasDocRecord> getRecordType() {
        return OasDocRecord.class;
    }

    /**
     * The column <code>oagi.oas_doc.oas_doc_id</code>. The primary key of the
     * record.
     */
    public final TableField<OasDocRecord, ULong> OAS_DOC_ID = createField(DSL.name("oas_doc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_doc.guid</code>. The GUID of the record.
     */
    public final TableField<OasDocRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.VARCHAR(41).nullable(false), this, "The GUID of the record.");

    /**
     * The column <code>oagi.oas_doc.open_api_version</code>. REQUIRED. This
     * string MUST be the semantic version number of the OpenAPI Specification
     * version that the OpenAPI document uses. The openapi field SHOULD be used
     * by tooling specifications and clients to interpret the OpenAPI document.
     * This is not related to the API info.version string.
     */
    public final TableField<OasDocRecord, String> OPEN_API_VERSION = createField(DSL.name("open_api_version"), SQLDataType.VARCHAR(20).nullable(false), this, "REQUIRED. This string MUST be the semantic version number of the OpenAPI Specification version that the OpenAPI document uses. The openapi field SHOULD be used by tooling specifications and clients to interpret the OpenAPI document. This is not related to the API info.version string.");

    /**
     * The column <code>oagi.oas_doc.title</code>. The title of the API.
     */
    public final TableField<OasDocRecord, String> TITLE = createField(DSL.name("title"), SQLDataType.CLOB.nullable(false), this, "The title of the API.");

    /**
     * The column <code>oagi.oas_doc.description</code>. A short description of
     * the API. CommonMark syntax MAY be used for rich text representation.
     */
    public final TableField<OasDocRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB.nullable(false), this, "A short description of the API. CommonMark syntax MAY be used for rich text representation.");

    /**
     * The column <code>oagi.oas_doc.terms_of_service</code>. A URL to the Terms
     * of Service for the API. MUST be in the format of a URL.
     */
    public final TableField<OasDocRecord, String> TERMS_OF_SERVICE = createField(DSL.name("terms_of_service"), SQLDataType.VARCHAR(250).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "A URL to the Terms of Service for the API. MUST be in the format of a URL.");

    /**
     * The column <code>oagi.oas_doc.version</code>. REQUIRED. The version of
     * the OpenAPI document (which is distinct from the OpenAPI Specification
     * version or the API implementation version).
     */
    public final TableField<OasDocRecord, String> VERSION = createField(DSL.name("version"), SQLDataType.VARCHAR(20).nullable(false), this, "REQUIRED. The version of the OpenAPI document (which is distinct from the OpenAPI Specification version or the API implementation version).");

    /**
     * The column <code>oagi.oas_doc.contact_name</code>. The identifying name
     * of the contact person/organization.
     */
    public final TableField<OasDocRecord, String> CONTACT_NAME = createField(DSL.name("contact_name"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "The identifying name of the contact person/organization.");

    /**
     * The column <code>oagi.oas_doc.contact_url</code>. The URL pointing to the
     * contact information. MUST be in the format of a URL.
     */
    public final TableField<OasDocRecord, String> CONTACT_URL = createField(DSL.name("contact_url"), SQLDataType.VARCHAR(250).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "The URL pointing to the contact information. MUST be in the format of a URL.");

    /**
     * The column <code>oagi.oas_doc.contact_email</code>. The email address of
     * the contact person/organization. MUST be in the format of an email
     * address.
     */
    public final TableField<OasDocRecord, String> CONTACT_EMAIL = createField(DSL.name("contact_email"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "The email address of the contact person/organization. MUST be in the format of an email address.");

    /**
     * The column <code>oagi.oas_doc.license_name</code>. REQUIRED if the
     * license used for the API. The license name used for the API.
     */
    public final TableField<OasDocRecord, String> LICENSE_NAME = createField(DSL.name("license_name"), SQLDataType.VARCHAR(100).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "REQUIRED if the license used for the API. The license name used for the API.");

    /**
     * The column <code>oagi.oas_doc.license_url</code>. A URL to the license
     * used for the API. MUST be in the format of a URL.
     */
    public final TableField<OasDocRecord, String> LICENSE_URL = createField(DSL.name("license_url"), SQLDataType.VARCHAR(250).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "A URL to the license used for the API. MUST be in the format of a URL.");

    /**
     * The column <code>oagi.oas_doc.owner_user_id</code>. The user who owns the
     * record.
     */
    public final TableField<OasDocRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who owns the record.");

    /**
     * The column <code>oagi.oas_doc.created_by</code>. The user who creates the
     * record.
     */
    public final TableField<OasDocRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who creates the record.");

    /**
     * The column <code>oagi.oas_doc.last_updated_by</code>. The user who last
     * updates the record.
     */
    public final TableField<OasDocRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who last updates the record.");

    /**
     * The column <code>oagi.oas_doc.creation_timestamp</code>. The timestamp
     * when the record is created.
     */
    public final TableField<OasDocRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is created.");

    /**
     * The column <code>oagi.oas_doc.last_update_timestamp</code>. The timestamp
     * when the record is last updated.
     */
    public final TableField<OasDocRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is last updated.");

    private OasDoc(Name alias, Table<OasDocRecord> aliased) {
        this(alias, aliased, null);
    }

    private OasDoc(Name alias, Table<OasDocRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oas_doc</code> table reference
     */
    public OasDoc(String alias) {
        this(DSL.name(alias), OAS_DOC);
    }

    /**
     * Create an aliased <code>oagi.oas_doc</code> table reference
     */
    public OasDoc(Name alias) {
        this(alias, OAS_DOC);
    }

    /**
     * Create a <code>oagi.oas_doc</code> table reference
     */
    public OasDoc() {
        this(DSL.name("oas_doc"), null);
    }

    public <O extends Record> OasDoc(Table<O> child, ForeignKey<O, OasDocRecord> key) {
        super(child, key, OAS_DOC);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<OasDocRecord, ULong> getIdentity() {
        return (Identity<OasDocRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<OasDocRecord> getPrimaryKey() {
        return Keys.KEY_OAS_DOC_PRIMARY;
    }

    @Override
    public List<ForeignKey<OasDocRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAS_DOC_OWNER_USER_ID_FK, Keys.OAS_DOC_CREATED_BY_FK, Keys.OAS_DOC_LAST_UPDATED_BY_FK);
    }

    private transient AppUser _oasDocOwnerUserIdFk;
    private transient AppUser _oasDocCreatedByFk;
    private transient AppUser _oasDocLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_owner_user_id_fk</code> key.
     */
    public AppUser oasDocOwnerUserIdFk() {
        if (_oasDocOwnerUserIdFk == null)
            _oasDocOwnerUserIdFk = new AppUser(this, Keys.OAS_DOC_OWNER_USER_ID_FK);

        return _oasDocOwnerUserIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_created_by_fk</code> key.
     */
    public AppUser oasDocCreatedByFk() {
        if (_oasDocCreatedByFk == null)
            _oasDocCreatedByFk = new AppUser(this, Keys.OAS_DOC_CREATED_BY_FK);

        return _oasDocCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_last_updated_by_fk</code> key.
     */
    public AppUser oasDocLastUpdatedByFk() {
        if (_oasDocLastUpdatedByFk == null)
            _oasDocLastUpdatedByFk = new AppUser(this, Keys.OAS_DOC_LAST_UPDATED_BY_FK);

        return _oasDocLastUpdatedByFk;
    }

    @Override
    public OasDoc as(String alias) {
        return new OasDoc(DSL.name(alias), this);
    }

    @Override
    public OasDoc as(Name alias) {
        return new OasDoc(alias, this);
    }

    @Override
    public OasDoc as(Table<?> alias) {
        return new OasDoc(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDoc rename(String name) {
        return new OasDoc(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDoc rename(Name name) {
        return new OasDoc(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDoc rename(Table<?> name) {
        return new OasDoc(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row17 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row17<ULong, String, String, String, String, String, String, String, String, String, String, String, ULong, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row17) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function17<? super ULong, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function17<? super ULong, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super String, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
