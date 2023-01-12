/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record3;
import org.jooq.Row3;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.TenantBusinessCtx;


/**
 * This table captures the tenant role and theirs bussiness contexts.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class TenantBusinessCtxRecord extends UpdatableRecordImpl<TenantBusinessCtxRecord> implements Record3<ULong, ULong, ULong> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>oagi.tenant_business_ctx.tenant_business_ctx_id</code>.
     * Primary key column.
     */
    public void setTenantBusinessCtxId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for <code>oagi.tenant_business_ctx.tenant_business_ctx_id</code>.
     * Primary key column.
     */
    public ULong getTenantBusinessCtxId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.tenant_business_ctx.tenant_id</code>. Tenant role.
     */
    public void setTenantId(ULong value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.tenant_business_ctx.tenant_id</code>. Tenant role.
     */
    public ULong getTenantId() {
        return (ULong) get(1);
    }

    /**
     * Setter for <code>oagi.tenant_business_ctx.biz_ctx_id</code>. Concrete
     * bussiness contaxt for the company.
     */
    public void setBizCtxId(ULong value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.tenant_business_ctx.biz_ctx_id</code>. Concrete
     * bussiness contaxt for the company.
     */
    public ULong getBizCtxId() {
        return (ULong) get(2);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record3 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row3<ULong, ULong, ULong> fieldsRow() {
        return (Row3) super.fieldsRow();
    }

    @Override
    public Row3<ULong, ULong, ULong> valuesRow() {
        return (Row3) super.valuesRow();
    }

    @Override
    public Field<ULong> field1() {
        return TenantBusinessCtx.TENANT_BUSINESS_CTX.TENANT_BUSINESS_CTX_ID;
    }

    @Override
    public Field<ULong> field2() {
        return TenantBusinessCtx.TENANT_BUSINESS_CTX.TENANT_ID;
    }

    @Override
    public Field<ULong> field3() {
        return TenantBusinessCtx.TENANT_BUSINESS_CTX.BIZ_CTX_ID;
    }

    @Override
    public ULong component1() {
        return getTenantBusinessCtxId();
    }

    @Override
    public ULong component2() {
        return getTenantId();
    }

    @Override
    public ULong component3() {
        return getBizCtxId();
    }

    @Override
    public ULong value1() {
        return getTenantBusinessCtxId();
    }

    @Override
    public ULong value2() {
        return getTenantId();
    }

    @Override
    public ULong value3() {
        return getBizCtxId();
    }

    @Override
    public TenantBusinessCtxRecord value1(ULong value) {
        setTenantBusinessCtxId(value);
        return this;
    }

    @Override
    public TenantBusinessCtxRecord value2(ULong value) {
        setTenantId(value);
        return this;
    }

    @Override
    public TenantBusinessCtxRecord value3(ULong value) {
        setBizCtxId(value);
        return this;
    }

    @Override
    public TenantBusinessCtxRecord values(ULong value1, ULong value2, ULong value3) {
        value1(value1);
        value2(value2);
        value3(value3);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached TenantBusinessCtxRecord
     */
    public TenantBusinessCtxRecord() {
        super(TenantBusinessCtx.TENANT_BUSINESS_CTX);
    }

    /**
     * Create a detached, initialised TenantBusinessCtxRecord
     */
    public TenantBusinessCtxRecord(ULong tenantBusinessCtxId, ULong tenantId, ULong bizCtxId) {
        super(TenantBusinessCtx.TENANT_BUSINESS_CTX);

        setTenantBusinessCtxId(tenantBusinessCtxId);
        setTenantId(tenantId);
        setBizCtxId(bizCtxId);
    }
}