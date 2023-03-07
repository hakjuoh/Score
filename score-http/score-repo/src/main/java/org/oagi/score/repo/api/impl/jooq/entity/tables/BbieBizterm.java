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
import org.jooq.Function9;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row9;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.BbieBiztermRecord;


/**
 * The bbie_bizterm table stores information about the aggregation between the
 * bbie_bizterm and BBIE. TODO: Placeholder, definition is missing.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class BbieBizterm extends TableImpl<BbieBiztermRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.bbie_bizterm</code>
     */
    public static final BbieBizterm BBIE_BIZTERM = new BbieBizterm();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<BbieBiztermRecord> getRecordType() {
        return BbieBiztermRecord.class;
    }

    /**
     * The column <code>oagi.bbie_bizterm.bbie_bizterm_id</code>. An internal,
     * primary database key of an bbie_bizterm record.
     */
    public final TableField<BbieBiztermRecord, ULong> BBIE_BIZTERM_ID = createField(DSL.name("bbie_bizterm_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "An internal, primary database key of an bbie_bizterm record.");

    /**
     * The column <code>oagi.bbie_bizterm.bcc_bizterm_id</code>. An internal ID
     * of the bbie_bizterm record.
     */
    public final TableField<BbieBiztermRecord, ULong> BCC_BIZTERM_ID = createField(DSL.name("bcc_bizterm_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "An internal ID of the bbie_bizterm record.");

    /**
     * The column <code>oagi.bbie_bizterm.bbie_id</code>. An internal ID of the
     * associated BBIE
     */
    public final TableField<BbieBiztermRecord, ULong> BBIE_ID = createField(DSL.name("bbie_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "An internal ID of the associated BBIE");

    /**
     * The column <code>oagi.bbie_bizterm.primary_indicator</code>. The
     * indicator shows if the business term is primary for the assigned BBIE.
     */
    public final TableField<BbieBiztermRecord, Byte> PRIMARY_INDICATOR = createField(DSL.name("primary_indicator"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.inline("0", SQLDataType.TINYINT)), this, "The indicator shows if the business term is primary for the assigned BBIE.");

    /**
     * The column <code>oagi.bbie_bizterm.type_code</code>. The type code of the
     * assignment.
     */
    public final TableField<BbieBiztermRecord, String> TYPE_CODE = createField(DSL.name("type_code"), SQLDataType.CHAR(30), this, "The type code of the assignment.");

    /**
     * The column <code>oagi.bbie_bizterm.created_by</code>. A foreign key
     * referring to the user who creates the bbie_bizterm record. The creator of
     * the asbie_bizterm is also its owner by default.
     */
    public final TableField<BbieBiztermRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key referring to the user who creates the bbie_bizterm record. The creator of the asbie_bizterm is also its owner by default.");

    /**
     * The column <code>oagi.bbie_bizterm.last_updated_by</code>. A foreign key
     * referring to the last user who has updated the bbie_bizterm record. This
     * may be the user who is in the same group as the creator.
     */
    public final TableField<BbieBiztermRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key referring to the last user who has updated the bbie_bizterm record. This may be the user who is in the same group as the creator.");

    /**
     * The column <code>oagi.bbie_bizterm.creation_timestamp</code>. Timestamp
     * when the bbie_bizterm record was first created.
     */
    public final TableField<BbieBiztermRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "Timestamp when the bbie_bizterm record was first created.");

    /**
     * The column <code>oagi.bbie_bizterm.last_update_timestamp</code>. The
     * timestamp when the bbie_bizterm was last updated.
     */
    public final TableField<BbieBiztermRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the bbie_bizterm was last updated.");

    private BbieBizterm(Name alias, Table<BbieBiztermRecord> aliased) {
        this(alias, aliased, null);
    }

    private BbieBizterm(Name alias, Table<BbieBiztermRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("The bbie_bizterm table stores information about the aggregation between the bbie_bizterm and BBIE. TODO: Placeholder, definition is missing."), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.bbie_bizterm</code> table reference
     */
    public BbieBizterm(String alias) {
        this(DSL.name(alias), BBIE_BIZTERM);
    }

    /**
     * Create an aliased <code>oagi.bbie_bizterm</code> table reference
     */
    public BbieBizterm(Name alias) {
        this(alias, BBIE_BIZTERM);
    }

    /**
     * Create a <code>oagi.bbie_bizterm</code> table reference
     */
    public BbieBizterm() {
        this(DSL.name("bbie_bizterm"), null);
    }

    public <O extends Record> BbieBizterm(Table<O> child, ForeignKey<O, BbieBiztermRecord> key) {
        super(child, key, BBIE_BIZTERM);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.BBIE_BIZTERM_ASBIE_BIZTERM_ASBIE_FK);
    }

    @Override
    public Identity<BbieBiztermRecord, ULong> getIdentity() {
        return (Identity<BbieBiztermRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<BbieBiztermRecord> getPrimaryKey() {
        return Keys.KEY_BBIE_BIZTERM_PRIMARY;
    }

    @Override
    public List<ForeignKey<BbieBiztermRecord, ?>> getReferences() {
        return Arrays.asList(Keys.BBIE_BIZTERM_BCC_BIZTERM_FK, Keys.BBIE_BIZTERM_BBIE_FK);
    }

    private transient BccBizterm _bccBizterm;
    private transient Bbie _bbie;

    /**
     * Get the implicit join path to the <code>oagi.bcc_bizterm</code> table.
     */
    public BccBizterm bccBizterm() {
        if (_bccBizterm == null)
            _bccBizterm = new BccBizterm(this, Keys.BBIE_BIZTERM_BCC_BIZTERM_FK);

        return _bccBizterm;
    }

    /**
     * Get the implicit join path to the <code>oagi.bbie</code> table.
     */
    public Bbie bbie() {
        if (_bbie == null)
            _bbie = new Bbie(this, Keys.BBIE_BIZTERM_BBIE_FK);

        return _bbie;
    }

    @Override
    public BbieBizterm as(String alias) {
        return new BbieBizterm(DSL.name(alias), this);
    }

    @Override
    public BbieBizterm as(Name alias) {
        return new BbieBizterm(alias, this);
    }

    @Override
    public BbieBizterm as(Table<?> alias) {
        return new BbieBizterm(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public BbieBizterm rename(String name) {
        return new BbieBizterm(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public BbieBizterm rename(Name name) {
        return new BbieBizterm(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public BbieBizterm rename(Table<?> name) {
        return new BbieBizterm(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row9 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row9<ULong, ULong, ULong, Byte, String, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row9) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function9<? super ULong, ? super ULong, ? super ULong, ? super Byte, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function9<? super ULong, ? super ULong, ? super ULong, ? super Byte, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}