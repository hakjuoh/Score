/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.e2e.impl.api.jooq.entity.tables;


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
import org.oagi.score.e2e.impl.api.jooq.entity.Keys;
import org.oagi.score.e2e.impl.api.jooq.entity.Oagi;
import org.oagi.score.e2e.impl.api.jooq.entity.tables.records.NamespaceRecord;


/**
 * This table stores information about a namespace. Namespace is the namespace
 * as in the XML schema specification.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Namespace extends TableImpl<NamespaceRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.namespace</code>
     */
    public static final Namespace NAMESPACE = new Namespace();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<NamespaceRecord> getRecordType() {
        return NamespaceRecord.class;
    }

    /**
     * The column <code>oagi.namespace.namespace_id</code>. Primary, internal
     * database key.
     */
    public final TableField<NamespaceRecord, ULong> NAMESPACE_ID = createField(DSL.name("namespace_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Primary, internal database key.");

    /**
     * The column <code>oagi.namespace.uri</code>. This is the URI of the
     * namespace.
     */
    public final TableField<NamespaceRecord, String> URI = createField(DSL.name("uri"), SQLDataType.VARCHAR(100).nullable(false), this, "This is the URI of the namespace.");

    /**
     * The column <code>oagi.namespace.prefix</code>. This is a default short
     * name to represent the URI. It may be overridden during the expression
     * generation. Null or empty means the same thing like the default prefix in
     * an XML schema.
     */
    public final TableField<NamespaceRecord, String> PREFIX = createField(DSL.name("prefix"), SQLDataType.VARCHAR(45).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This is a default short name to represent the URI. It may be overridden during the expression generation. Null or empty means the same thing like the default prefix in an XML schema.");

    /**
     * The column <code>oagi.namespace.description</code>. Description or
     * explanation about the namespace or use of the namespace.
     */
    public final TableField<NamespaceRecord, String> DESCRIPTION = createField(DSL.name("description"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "Description or explanation about the namespace or use of the namespace.");

    /**
     * The column <code>oagi.namespace.is_std_nmsp</code>. This indicates
     * whether the namespace is reserved for standard used (i.e., whether it is
     * an OAGIS namespace). If it is true, then end users cannot user the
     * namespace for the end user CCs.
     */
    public final TableField<NamespaceRecord, Byte> IS_STD_NMSP = createField(DSL.name("is_std_nmsp"), SQLDataType.TINYINT.nullable(false).defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "This indicates whether the namespace is reserved for standard used (i.e., whether it is an OAGIS namespace). If it is true, then end users cannot user the namespace for the end user CCs.");

    /**
     * The column <code>oagi.namespace.owner_user_id</code>. Foreign key to the
     * APP_USER table identifying the user who can update or delete the record.
     */
    public final TableField<NamespaceRecord, ULong> OWNER_USER_ID = createField(DSL.name("owner_user_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table identifying the user who can update or delete the record.");

    /**
     * The column <code>oagi.namespace.created_by</code>. Foreign key to the
     * APP_USER table identifying user who created the namespace.
     */
    public final TableField<NamespaceRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table identifying user who created the namespace.");

    /**
     * The column <code>oagi.namespace.last_updated_by</code>. Foreign key to
     * the APP_USER table identifying the user who last updated the record.
     */
    public final TableField<NamespaceRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table identifying the user who last updated the record.");

    /**
     * The column <code>oagi.namespace.creation_timestamp</code>. The timestamp
     * when the record was first created.
     */
    public final TableField<NamespaceRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was first created.");

    /**
     * The column <code>oagi.namespace.last_update_timestamp</code>. The
     * timestamp when the record was last updated.
     */
    public final TableField<NamespaceRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was last updated.");

    private Namespace(Name alias, Table<NamespaceRecord> aliased) {
        this(alias, aliased, null);
    }

    private Namespace(Name alias, Table<NamespaceRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("This table stores information about a namespace. Namespace is the namespace as in the XML schema specification."), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.namespace</code> table reference
     */
    public Namespace(String alias) {
        this(DSL.name(alias), NAMESPACE);
    }

    /**
     * Create an aliased <code>oagi.namespace</code> table reference
     */
    public Namespace(Name alias) {
        this(alias, NAMESPACE);
    }

    /**
     * Create a <code>oagi.namespace</code> table reference
     */
    public Namespace() {
        this(DSL.name("namespace"), null);
    }

    public <O extends Record> Namespace(Table<O> child, ForeignKey<O, NamespaceRecord> key) {
        super(child, key, NAMESPACE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<NamespaceRecord, ULong> getIdentity() {
        return (Identity<NamespaceRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<NamespaceRecord> getPrimaryKey() {
        return Keys.KEY_NAMESPACE_PRIMARY;
    }

    @Override
    public List<UniqueKey<NamespaceRecord>> getUniqueKeys() {
        return Arrays.asList(Keys.KEY_NAMESPACE_NAMESPACE_UK1);
    }

    @Override
    public List<ForeignKey<NamespaceRecord, ?>> getReferences() {
        return Arrays.asList(Keys.NAMESPACE_OWNER_USER_ID_FK, Keys.NAMESPACE_CREATED_BY_FK, Keys.NAMESPACE_LAST_UPDATED_BY_FK);
    }

    private transient AppUser _namespaceOwnerUserIdFk;
    private transient AppUser _namespaceCreatedByFk;
    private transient AppUser _namespaceLastUpdatedByFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>namespace_owner_user_id_fk</code> key.
     */
    public AppUser namespaceOwnerUserIdFk() {
        if (_namespaceOwnerUserIdFk == null)
            _namespaceOwnerUserIdFk = new AppUser(this, Keys.NAMESPACE_OWNER_USER_ID_FK);

        return _namespaceOwnerUserIdFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>namespace_created_by_fk</code> key.
     */
    public AppUser namespaceCreatedByFk() {
        if (_namespaceCreatedByFk == null)
            _namespaceCreatedByFk = new AppUser(this, Keys.NAMESPACE_CREATED_BY_FK);

        return _namespaceCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>namespace_last_updated_by_fk</code> key.
     */
    public AppUser namespaceLastUpdatedByFk() {
        if (_namespaceLastUpdatedByFk == null)
            _namespaceLastUpdatedByFk = new AppUser(this, Keys.NAMESPACE_LAST_UPDATED_BY_FK);

        return _namespaceLastUpdatedByFk;
    }

    @Override
    public Namespace as(String alias) {
        return new Namespace(DSL.name(alias), this);
    }

    @Override
    public Namespace as(Name alias) {
        return new Namespace(alias, this);
    }

    @Override
    public Namespace as(Table<?> alias) {
        return new Namespace(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Namespace rename(String name) {
        return new Namespace(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Namespace rename(Name name) {
        return new Namespace(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Namespace rename(Table<?> name) {
        return new Namespace(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row10 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row10<ULong, String, String, String, Byte, ULong, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row10) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function10<? super ULong, ? super String, ? super String, ? super String, ? super Byte, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function10<? super ULong, ? super String, ? super String, ? super String, ? super Byte, ? super ULong, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
