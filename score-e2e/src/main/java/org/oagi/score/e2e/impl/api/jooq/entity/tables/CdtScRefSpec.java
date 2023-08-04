/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.e2e.impl.api.jooq.entity.tables;


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
import org.oagi.score.e2e.impl.api.jooq.entity.Keys;
import org.oagi.score.e2e.impl.api.jooq.entity.Oagi;
import org.oagi.score.e2e.impl.api.jooq.entity.tables.records.CdtScRefSpecRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class CdtScRefSpec extends TableImpl<CdtScRefSpecRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.cdt_sc_ref_spec</code>
     */
    public static final CdtScRefSpec CDT_SC_REF_SPEC = new CdtScRefSpec();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<CdtScRefSpecRecord> getRecordType() {
        return CdtScRefSpecRecord.class;
    }

    /**
     * The column <code>oagi.cdt_sc_ref_spec.cdt_sc_ref_spec_id</code>.
     */
    public final TableField<CdtScRefSpecRecord, ULong> CDT_SC_REF_SPEC_ID = createField(DSL.name("cdt_sc_ref_spec_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.cdt_sc_ref_spec.cdt_sc_id</code>.
     */
    public final TableField<CdtScRefSpecRecord, ULong> CDT_SC_ID = createField(DSL.name("cdt_sc_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    /**
     * The column <code>oagi.cdt_sc_ref_spec.ref_spec_id</code>.
     */
    public final TableField<CdtScRefSpecRecord, ULong> REF_SPEC_ID = createField(DSL.name("ref_spec_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "");

    private CdtScRefSpec(Name alias, Table<CdtScRefSpecRecord> aliased) {
        this(alias, aliased, null);
    }

    private CdtScRefSpec(Name alias, Table<CdtScRefSpecRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.cdt_sc_ref_spec</code> table reference
     */
    public CdtScRefSpec(String alias) {
        this(DSL.name(alias), CDT_SC_REF_SPEC);
    }

    /**
     * Create an aliased <code>oagi.cdt_sc_ref_spec</code> table reference
     */
    public CdtScRefSpec(Name alias) {
        this(alias, CDT_SC_REF_SPEC);
    }

    /**
     * Create a <code>oagi.cdt_sc_ref_spec</code> table reference
     */
    public CdtScRefSpec() {
        this(DSL.name("cdt_sc_ref_spec"), null);
    }

    public <O extends Record> CdtScRefSpec(Table<O> child, ForeignKey<O, CdtScRefSpecRecord> key) {
        super(child, key, CDT_SC_REF_SPEC);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<CdtScRefSpecRecord, ULong> getIdentity() {
        return (Identity<CdtScRefSpecRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<CdtScRefSpecRecord> getPrimaryKey() {
        return Keys.KEY_CDT_SC_REF_SPEC_PRIMARY;
    }

    @Override
    public List<ForeignKey<CdtScRefSpecRecord, ?>> getReferences() {
        return Arrays.asList(Keys.CDT_SC_REF_SPEC_CDT_SC_ID_FK, Keys.CDT_SC_REF_SPEC_REF_SPEC_ID_FK);
    }

    private transient DtSc _dtSc;
    private transient RefSpec _refSpec;

    /**
     * Get the implicit join path to the <code>oagi.dt_sc</code> table.
     */
    public DtSc dtSc() {
        if (_dtSc == null)
            _dtSc = new DtSc(this, Keys.CDT_SC_REF_SPEC_CDT_SC_ID_FK);

        return _dtSc;
    }

    /**
     * Get the implicit join path to the <code>oagi.ref_spec</code> table.
     */
    public RefSpec refSpec() {
        if (_refSpec == null)
            _refSpec = new RefSpec(this, Keys.CDT_SC_REF_SPEC_REF_SPEC_ID_FK);

        return _refSpec;
    }

    @Override
    public CdtScRefSpec as(String alias) {
        return new CdtScRefSpec(DSL.name(alias), this);
    }

    @Override
    public CdtScRefSpec as(Name alias) {
        return new CdtScRefSpec(alias, this);
    }

    @Override
    public CdtScRefSpec as(Table<?> alias) {
        return new CdtScRefSpec(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public CdtScRefSpec rename(String name) {
        return new CdtScRefSpec(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public CdtScRefSpec rename(Name name) {
        return new CdtScRefSpec(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public CdtScRefSpec rename(Table<?> name) {
        return new CdtScRefSpec(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row3 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row3<ULong, ULong, ULong> fieldsRow() {
        return (Row3) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function3<? super ULong, ? super ULong, ? super ULong, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function3<? super ULong, ? super ULong, ? super ULong, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
