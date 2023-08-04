/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function2;
import org.jooq.Identity;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row2;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.RefSpecRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class RefSpec extends TableImpl<RefSpecRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.ref_spec</code>
     */
    public static final RefSpec REF_SPEC = new RefSpec();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<RefSpecRecord> getRecordType() {
        return RefSpecRecord.class;
    }

    /**
     * The column <code>oagi.ref_spec.ref_spec_id</code>.
     */
    public final TableField<RefSpecRecord, ULong> REF_SPEC_ID = createField(DSL.name("ref_spec_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.ref_spec.spec</code>.
     */
    public final TableField<RefSpecRecord, String> SPEC = createField(DSL.name("spec"), SQLDataType.VARCHAR(30).nullable(false).defaultValue(DSL.field(DSL.raw("''"), SQLDataType.VARCHAR)), this, "");

    private RefSpec(Name alias, Table<RefSpecRecord> aliased) {
        this(alias, aliased, null);
    }

    private RefSpec(Name alias, Table<RefSpecRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.ref_spec</code> table reference
     */
    public RefSpec(String alias) {
        this(DSL.name(alias), REF_SPEC);
    }

    /**
     * Create an aliased <code>oagi.ref_spec</code> table reference
     */
    public RefSpec(Name alias) {
        this(alias, REF_SPEC);
    }

    /**
     * Create a <code>oagi.ref_spec</code> table reference
     */
    public RefSpec() {
        this(DSL.name("ref_spec"), null);
    }

    public <O extends Record> RefSpec(Table<O> child, ForeignKey<O, RefSpecRecord> key) {
        super(child, key, REF_SPEC);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<RefSpecRecord, ULong> getIdentity() {
        return (Identity<RefSpecRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<RefSpecRecord> getPrimaryKey() {
        return Keys.KEY_REF_SPEC_PRIMARY;
    }

    @Override
    public RefSpec as(String alias) {
        return new RefSpec(DSL.name(alias), this);
    }

    @Override
    public RefSpec as(Name alias) {
        return new RefSpec(alias, this);
    }

    @Override
    public RefSpec as(Table<?> alias) {
        return new RefSpec(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public RefSpec rename(String name) {
        return new RefSpec(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public RefSpec rename(Name name) {
        return new RefSpec(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public RefSpec rename(Table<?> name) {
        return new RefSpec(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row2 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row2<ULong, String> fieldsRow() {
        return (Row2) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function2<? super ULong, ? super String, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function2<? super ULong, ? super String, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
