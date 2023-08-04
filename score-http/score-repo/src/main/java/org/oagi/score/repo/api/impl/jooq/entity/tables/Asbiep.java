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
import org.jooq.Function14;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row14;
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
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.AsbiepRecord;


/**
 * ASBIEP represents a role in a usage of an ABIE. It is a contextualization of
 * an ASCCP.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Asbiep extends TableImpl<AsbiepRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.asbiep</code>
     */
    public static final Asbiep ASBIEP = new Asbiep();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<AsbiepRecord> getRecordType() {
        return AsbiepRecord.class;
    }

    /**
     * The column <code>oagi.asbiep.asbiep_id</code>. A internal, primary
     * database key of an ASBIEP.
     */
    public final TableField<AsbiepRecord, ULong> ASBIEP_ID = createField(DSL.name("asbiep_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "A internal, primary database key of an ASBIEP.");

    /**
     * The column <code>oagi.asbiep.guid</code>. A globally unique identifier
     * (GUID).
     */
    public final TableField<AsbiepRecord, String> GUID = createField(DSL.name("guid"), SQLDataType.CHAR(32).nullable(false), this, "A globally unique identifier (GUID).");

    /**
     * The column <code>oagi.asbiep.based_asccp_manifest_id</code>. A foreign
     * key pointing to the ASCCP_MANIFEST record. It is the ASCCP, on which the
     * ASBIEP contextualizes.
     */
    public final TableField<AsbiepRecord, ULong> BASED_ASCCP_MANIFEST_ID = createField(DSL.name("based_asccp_manifest_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key pointing to the ASCCP_MANIFEST record. It is the ASCCP, on which the ASBIEP contextualizes.");

    /**
     * The column <code>oagi.asbiep.path</code>.
     */
    public final TableField<AsbiepRecord, String> PATH = createField(DSL.name("path"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "");

    /**
     * The column <code>oagi.asbiep.hash_path</code>. hash_path generated from
     * the path of the component graph using hash function, so that it is unique
     * in the graph.
     */
    public final TableField<AsbiepRecord, String> HASH_PATH = createField(DSL.name("hash_path"), SQLDataType.VARCHAR(64).nullable(false), this, "hash_path generated from the path of the component graph using hash function, so that it is unique in the graph.");

    /**
     * The column <code>oagi.asbiep.role_of_abie_id</code>. A foreign key
     * pointing to the ABIE record. It is the ABIE, which the property term in
     * the based ASCCP qualifies. Note that the ABIE has to be derived from the
     * ACC used by the based ASCCP.
     */
    public final TableField<AsbiepRecord, ULong> ROLE_OF_ABIE_ID = createField(DSL.name("role_of_abie_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key pointing to the ABIE record. It is the ABIE, which the property term in the based ASCCP qualifies. Note that the ABIE has to be derived from the ACC used by the based ASCCP.");

    /**
     * The column <code>oagi.asbiep.definition</code>. A definition to override
     * the ASCCP's definition. If NULL, it means that the definition should be
     * derived from the based ASCCP on the UI, expression generation, and any
     * API.
     */
    public final TableField<AsbiepRecord, String> DEFINITION = createField(DSL.name("definition"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "A definition to override the ASCCP's definition. If NULL, it means that the definition should be derived from the based ASCCP on the UI, expression generation, and any API.");

    /**
     * The column <code>oagi.asbiep.remark</code>. This column allows the user
     * to specify a context-specific usage of the BIE. It is different from the
     * DEFINITION column in that the DEFINITION column is a description
     * conveying the meaning of the associated concept. Remarks may be a very
     * implementation specific instruction or others. For example, BOM BOD, as
     * an ACC, is a generic BOM structure. In a particular context, a BOM ASBIEP
     * can be a Super BOM. Explanation of the Super BOM concept should be
     * captured in the Definition of the ASBIEP. A remark about that ASBIEP may
     * be "Type of BOM should be recognized in the BOM/typeCode."
     */
    public final TableField<AsbiepRecord, String> REMARK = createField(DSL.name("remark"), SQLDataType.VARCHAR(225).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This column allows the user to specify a context-specific usage of the BIE. It is different from the DEFINITION column in that the DEFINITION column is a description conveying the meaning of the associated concept. Remarks may be a very implementation specific instruction or others. For example, BOM BOD, as an ACC, is a generic BOM structure. In a particular context, a BOM ASBIEP can be a Super BOM. Explanation of the Super BOM concept should be captured in the Definition of the ASBIEP. A remark about that ASBIEP may be \"Type of BOM should be recognized in the BOM/typeCode.\"");

    /**
     * The column <code>oagi.asbiep.biz_term</code>. This column represents a
     * business term to indicate what the BIE is called in a particular business
     * context. With this current design, only one business term is allowed per
     * business context.
     */
    public final TableField<AsbiepRecord, String> BIZ_TERM = createField(DSL.name("biz_term"), SQLDataType.VARCHAR(225).defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.VARCHAR)), this, "This column represents a business term to indicate what the BIE is called in a particular business context. With this current design, only one business term is allowed per business context.");

    /**
     * The column <code>oagi.asbiep.created_by</code>. A foreign key referring
     * to the user who creates the ASBIEP. The creator of the ASBIEP is also its
     * owner by default. ASBIEPs created as children of another ABIE have the
     * same CREATED_BY.
     */
    public final TableField<AsbiepRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key referring to the user who creates the ASBIEP. The creator of the ASBIEP is also its owner by default. ASBIEPs created as children of another ABIE have the same CREATED_BY.");

    /**
     * The column <code>oagi.asbiep.last_updated_by</code>. A foreign key
     * referring to the last user who has updated the ASBIEP record. 
     */
    public final TableField<AsbiepRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "A foreign key referring to the last user who has updated the ASBIEP record. ");

    /**
     * The column <code>oagi.asbiep.creation_timestamp</code>. Timestamp when
     * the ASBIEP record was first created. ASBIEPs created as children of
     * another ABIE have the same CREATION_TIMESTAMP.
     */
    public final TableField<AsbiepRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "Timestamp when the ASBIEP record was first created. ASBIEPs created as children of another ABIE have the same CREATION_TIMESTAMP.");

    /**
     * The column <code>oagi.asbiep.last_update_timestamp</code>. The timestamp
     * when the ASBIEP was last updated.
     */
    public final TableField<AsbiepRecord, LocalDateTime> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the ASBIEP was last updated.");

    /**
     * The column <code>oagi.asbiep.owner_top_level_asbiep_id</code>. This is a
     * foreign key to the top-level ASBIEP.
     */
    public final TableField<AsbiepRecord, ULong> OWNER_TOP_LEVEL_ASBIEP_ID = createField(DSL.name("owner_top_level_asbiep_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "This is a foreign key to the top-level ASBIEP.");

    private Asbiep(Name alias, Table<AsbiepRecord> aliased) {
        this(alias, aliased, null);
    }

    private Asbiep(Name alias, Table<AsbiepRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("ASBIEP represents a role in a usage of an ABIE. It is a contextualization of an ASCCP."), TableOptions.table());
    }

    /**
     * Create an aliased <code>oagi.asbiep</code> table reference
     */
    public Asbiep(String alias) {
        this(DSL.name(alias), ASBIEP);
    }

    /**
     * Create an aliased <code>oagi.asbiep</code> table reference
     */
    public Asbiep(Name alias) {
        this(alias, ASBIEP);
    }

    /**
     * Create a <code>oagi.asbiep</code> table reference
     */
    public Asbiep() {
        this(DSL.name("asbiep"), null);
    }

    public <O extends Record> Asbiep(Table<O> child, ForeignKey<O, AsbiepRecord> key) {
        super(child, key, ASBIEP);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.asList(Indexes.ASBIEP_ASBIEP_HASH_PATH_K, Indexes.ASBIEP_ASBIEP_PATH_K);
    }

    @Override
    public Identity<AsbiepRecord, ULong> getIdentity() {
        return (Identity<AsbiepRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<AsbiepRecord> getPrimaryKey() {
        return Keys.KEY_ASBIEP_PRIMARY;
    }

    @Override
    public List<ForeignKey<AsbiepRecord, ?>> getReferences() {
        return Arrays.asList(Keys.ASBIEP_BASED_ASCCP_MANIFEST_ID_FK, Keys.ASBIEP_ROLE_OF_ABIE_ID_FK, Keys.ASBIEP_CREATED_BY_FK, Keys.ASBIEP_LAST_UPDATED_BY_FK, Keys.ASBIEP_OWNER_TOP_LEVEL_ASBIEP_ID_FK);
    }

    private transient AsccpManifest _asccpManifest;
    private transient Abie _abie;
    private transient AppUser _asbiepCreatedByFk;
    private transient AppUser _asbiepLastUpdatedByFk;
    private transient TopLevelAsbiep _topLevelAsbiep;

    /**
     * Get the implicit join path to the <code>oagi.asccp_manifest</code> table.
     */
    public AsccpManifest asccpManifest() {
        if (_asccpManifest == null)
            _asccpManifest = new AsccpManifest(this, Keys.ASBIEP_BASED_ASCCP_MANIFEST_ID_FK);

        return _asccpManifest;
    }

    /**
     * Get the implicit join path to the <code>oagi.abie</code> table.
     */
    public Abie abie() {
        if (_abie == null)
            _abie = new Abie(this, Keys.ASBIEP_ROLE_OF_ABIE_ID_FK);

        return _abie;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>asbiep_created_by_fk</code> key.
     */
    public AppUser asbiepCreatedByFk() {
        if (_asbiepCreatedByFk == null)
            _asbiepCreatedByFk = new AppUser(this, Keys.ASBIEP_CREATED_BY_FK);

        return _asbiepCreatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>asbiep_last_updated_by_fk</code> key.
     */
    public AppUser asbiepLastUpdatedByFk() {
        if (_asbiepLastUpdatedByFk == null)
            _asbiepLastUpdatedByFk = new AppUser(this, Keys.ASBIEP_LAST_UPDATED_BY_FK);

        return _asbiepLastUpdatedByFk;
    }

    /**
     * Get the implicit join path to the <code>oagi.top_level_asbiep</code>
     * table.
     */
    public TopLevelAsbiep topLevelAsbiep() {
        if (_topLevelAsbiep == null)
            _topLevelAsbiep = new TopLevelAsbiep(this, Keys.ASBIEP_OWNER_TOP_LEVEL_ASBIEP_ID_FK);

        return _topLevelAsbiep;
    }

    @Override
    public Asbiep as(String alias) {
        return new Asbiep(DSL.name(alias), this);
    }

    @Override
    public Asbiep as(Name alias) {
        return new Asbiep(alias, this);
    }

    @Override
    public Asbiep as(Table<?> alias) {
        return new Asbiep(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Asbiep rename(String name) {
        return new Asbiep(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Asbiep rename(Name name) {
        return new Asbiep(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Asbiep rename(Table<?> name) {
        return new Asbiep(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row14 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row14<ULong, String, ULong, String, String, ULong, String, String, String, ULong, ULong, LocalDateTime, LocalDateTime, ULong> fieldsRow() {
        return (Row14) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function14<? super ULong, ? super String, ? super ULong, ? super String, ? super String, ? super ULong, ? super String, ? super String, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? super ULong, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function14<? super ULong, ? super String, ? super ULong, ? super String, ? super String, ? super ULong, ? super String, ? super String, ? super String, ? super ULong, ? super ULong, ? super LocalDateTime, ? super LocalDateTime, ? super ULong, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
