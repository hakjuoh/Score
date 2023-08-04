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
import org.jooq.Function6;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row6;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.OasDocBizCtxRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class OasDocBizCtx extends TableImpl<OasDocBizCtxRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oas_doc_biz_ctx</code>
     */
    public static final OasDocBizCtx OAS_DOC_BIZ_CTX = new OasDocBizCtx();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<OasDocBizCtxRecord> getRecordType() {
        return OasDocBizCtxRecord.class;
    }

    /**
     * The column <code>oagi.oas_doc_biz_ctx.oas_doc_id</code>. The primary key
     * of the record.
     */
    public final TableField<OasDocBizCtxRecord, ULong> OAS_DOC_ID = createField(DSL.name("oas_doc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_doc_biz_ctx.biz_ctx_id</code>. The primary key
     * of the record.
     */
    public final TableField<OasDocBizCtxRecord, ULong> BIZ_CTX_ID = createField(DSL.name("biz_ctx_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_doc_biz_ctx.created_by</code>. The user who
     * creates the record.
     */
    public final TableField<OasDocBizCtxRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who creates the record.");

    /**
     * The column <code>oagi.oas_doc_biz_ctx.last_updated_by</code>. The user
     * who last updates the record.
     */
    public final TableField<OasDocBizCtxRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who last updates the record.");

    /**
     * The column <code>oagi.oas_doc_biz_ctx.creation_timestamp</code>. The
     * timestamp when the record is created.
     */
    public final TableField<OasDocBizCtxRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is created.");

    /**
     * The column <code>oagi.oas_doc_biz_ctx.last_update_timestamp</code>. The
     * timestamp when the record is last updated.
     */
    public final TableField<OasDocBizCtxRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is last updated.");

    private OasDocBizCtx(Name alias, Table<OasDocBizCtxRecord> aliased) {
        this(alias, aliased, null);
    }

    private OasDocBizCtx(Name alias, Table<OasDocBizCtxRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oas_doc_biz_ctx</code> table reference
     */
    public OasDocBizCtx(String alias) {
        this(DSL.name(alias), OAS_DOC_BIZ_CTX);
    }

    /**
     * Create an aliased <code>oagi.oas_doc_biz_ctx</code> table reference
     */
    public OasDocBizCtx(Name alias) {
        this(alias, OAS_DOC_BIZ_CTX);
    }

    /**
     * Create a <code>oagi.oas_doc_biz_ctx</code> table reference
     */
    public OasDocBizCtx() {
        this(DSL.name("oas_doc_biz_ctx"), null);
    }

    public <O extends Record> OasDocBizCtx(Table<O> child, ForeignKey<O, OasDocBizCtxRecord> key) {
        super(child, key, OAS_DOC_BIZ_CTX);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public UniqueKey<OasDocBizCtxRecord> getPrimaryKey() {
        return Keys.KEY_OAS_DOC_BIZ_CTX_PRIMARY;
    }

    @Override
    public List<ForeignKey<OasDocBizCtxRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAS_DOC_BIZ_CTX_OAS_DOC_ID_FK, Keys.OAS_DOC_BIZ_CTX_BIZ_CTX_ID_FK, Keys.OAS_DOC_BIZ_CTX_CREATED_BY_FK, Keys.OAS_DOC_BIZ_CTX_LAST_UPDATED_BY_FK);
    }

    private transient OasDoc _oasDoc;
    private transient BizCtx _bizCtx;
    private transient AppUser _oasDocBizCtxCreatedByFk;
    private transient AppUser _oasDocBizCtxLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.oas_doc</code> table.
     */
    public OasDoc oasDoc() {
        if (_oasDoc == null)
            _oasDoc = new OasDoc(this, Keys.OAS_DOC_BIZ_CTX_OAS_DOC_ID_FK);

        return _oasDoc;
    }

    /**
     * Get the implicit join path to the <code>oagi.biz_ctx</code> table.
     */
    public BizCtx bizCtx() {
        if (_bizCtx == null)
            _bizCtx = new BizCtx(this, Keys.OAS_DOC_BIZ_CTX_BIZ_CTX_ID_FK);

        return _bizCtx;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_biz_ctx_created_by_fk</code> key.
     */
    public AppUser oasDocBizCtxCreatedByFk() {
        if (_oasDocBizCtxCreatedByFk == null)
            _oasDocBizCtxCreatedByFk = new AppUser(this, Keys.OAS_DOC_BIZ_CTX_CREATED_BY_FK);

        return _oasDocBizCtxCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_biz_ctx_last_updated_by_fk</code> key.
     */
    public AppUser oasDocBizCtxLastUpdatedByFk() {
        if (_oasDocBizCtxLastUpdatedByFk == null)
            _oasDocBizCtxLastUpdatedByFk = new AppUser(this, Keys.OAS_DOC_BIZ_CTX_LAST_UPDATED_BY_FK);

        return _oasDocBizCtxLastUpdatedByFk;
    }

    @Override
    public OasDocBizCtx as(String alias) {
        return new OasDocBizCtx(DSL.name(alias), this);
    }

    @Override
    public OasDocBizCtx as(Name alias) {
        return new OasDocBizCtx(alias, this);
    }

    @Override
    public OasDocBizCtx as(Table<?> alias) {
        return new OasDocBizCtx(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocBizCtx rename(String name) {
        return new OasDocBizCtx(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocBizCtx rename(Name name) {
        return new OasDocBizCtx(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocBizCtx rename(Table<?> name) {
        return new OasDocBizCtx(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row6 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row6<ULong, ULong, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row6) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function6<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function6<? super ULong, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
