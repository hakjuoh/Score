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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.OasDocTagRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class OasDocTag extends TableImpl<OasDocTagRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.oas_doc_tag</code>
     */
    public static final OasDocTag OAS_DOC_TAG = new OasDocTag();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<OasDocTagRecord> getRecordType() {
        return OasDocTagRecord.class;
    }

    /**
     * The column <code>oagi.oas_doc_tag.oas_doc_id</code>. The primary key of
     * the record.
     */
    public final TableField<OasDocTagRecord, ULong> OAS_DOC_ID = createField(DSL.name("oas_doc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_doc_tag.oas_tag_id</code>. The primary key of
     * the record.
     */
    public final TableField<OasDocTagRecord, ULong> OAS_TAG_ID = createField(DSL.name("oas_tag_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The primary key of the record.");

    /**
     * The column <code>oagi.oas_doc_tag.created_by</code>. The user who creates
     * the record.
     */
    public final TableField<OasDocTagRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who creates the record.");

    /**
     * The column <code>oagi.oas_doc_tag.last_updated_by</code>. The user who
     * last updates the record.
     */
    public final TableField<OasDocTagRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who last updates the record.");

    /**
     * The column <code>oagi.oas_doc_tag.creation_timestamp</code>. The
     * timestamp when the record is created.
     */
    public final TableField<OasDocTagRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is created.");

    /**
     * The column <code>oagi.oas_doc_tag.last_update_timestamp</code>. The
     * timestamp when the record is last updated.
     */
    public final TableField<OasDocTagRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record is last updated.");

    private OasDocTag(Name alias, Table<OasDocTagRecord> aliased) {
        this(alias, aliased, null);
    }

    private OasDocTag(Name alias, Table<OasDocTagRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.oas_doc_tag</code> table reference
     */
    public OasDocTag(String alias) {
        this(DSL.name(alias), OAS_DOC_TAG);
    }

    /**
     * Create an aliased <code>oagi.oas_doc_tag</code> table reference
     */
    public OasDocTag(Name alias) {
        this(alias, OAS_DOC_TAG);
    }

    /**
     * Create a <code>oagi.oas_doc_tag</code> table reference
     */
    public OasDocTag() {
        this(DSL.name("oas_doc_tag"), null);
    }

    public <O extends Record> OasDocTag(Table<O> child, ForeignKey<O, OasDocTagRecord> key) {
        super(child, key, OAS_DOC_TAG);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public UniqueKey<OasDocTagRecord> getPrimaryKey() {
        return Keys.KEY_OAS_DOC_TAG_PRIMARY;
    }

    @Override
    public List<ForeignKey<OasDocTagRecord, ?>> getReferences() {
        return Arrays.asList(Keys.OAS_DOC_TAG_OAS_DOC_ID_FK, Keys.OAS_DOC_TAG_OAS_TAG_ID_FK, Keys.OAS_DOC_TAG_CREATED_BY_FK, Keys.OAS_DOC_TAG_LAST_UPDATED_BY_FK);
    }

    private transient OasDoc _oasDoc;
    private transient OasTag _oasTag;
    private transient AppUser _oasDocTagCreatedByFk;
    private transient AppUser _oasDocTagLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.oas_doc</code> table.
     */
    public OasDoc oasDoc() {
        if (_oasDoc == null)
            _oasDoc = new OasDoc(this, Keys.OAS_DOC_TAG_OAS_DOC_ID_FK);

        return _oasDoc;
    }

    /**
     * Get the implicit join path to the <code>oagi.oas_tag</code> table.
     */
    public OasTag oasTag() {
        if (_oasTag == null)
            _oasTag = new OasTag(this, Keys.OAS_DOC_TAG_OAS_TAG_ID_FK);

        return _oasTag;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_tag_created_by_fk</code> key.
     */
    public AppUser oasDocTagCreatedByFk() {
        if (_oasDocTagCreatedByFk == null)
            _oasDocTagCreatedByFk = new AppUser(this, Keys.OAS_DOC_TAG_CREATED_BY_FK);

        return _oasDocTagCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>oas_doc_tag_last_updated_by_fk</code> key.
     */
    public AppUser oasDocTagLastUpdatedByFk() {
        if (_oasDocTagLastUpdatedByFk == null)
            _oasDocTagLastUpdatedByFk = new AppUser(this, Keys.OAS_DOC_TAG_LAST_UPDATED_BY_FK);

        return _oasDocTagLastUpdatedByFk;
    }

    @Override
    public OasDocTag as(String alias) {
        return new OasDocTag(DSL.name(alias), this);
    }

    @Override
    public OasDocTag as(Name alias) {
        return new OasDocTag(alias, this);
    }

    @Override
    public OasDocTag as(Table<?> alias) {
        return new OasDocTag(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocTag rename(String name) {
        return new OasDocTag(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocTag rename(Name name) {
        return new OasDocTag(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public OasDocTag rename(Table<?> name) {
        return new OasDocTag(name.getQualifiedName(), null);
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