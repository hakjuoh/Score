/*
 * This file is generated by jOOQ.
 */
package org.oagi.srt.entity.jooq.tables;


import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Generated;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.Index;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Row18;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.TableImpl;
import org.jooq.types.ULong;
import org.oagi.srt.entity.jooq.Indexes;
import org.oagi.srt.entity.jooq.Keys;
import org.oagi.srt.entity.jooq.Oagi;
import org.oagi.srt.entity.jooq.tables.records.CodeListRecord;


/**
 * This table stores information about a code list. When a code list is derived 
 * from another code list, the whole set of code values belonging to the based 
 * code list will be copied.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.12.1"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class CodeList extends TableImpl<CodeListRecord> {

    private static final long serialVersionUID = 981957569;

    /**
     * The reference instance of <code>oagi.code_list</code>
     */
    public static final CodeList CODE_LIST = new CodeList();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<CodeListRecord> getRecordType() {
        return CodeListRecord.class;
    }

    /**
     * The column <code>oagi.code_list.code_list_id</code>. Internal, primary database key.
     */
    public final TableField<CodeListRecord, ULong> CODE_LIST_ID = createField(DSL.name("code_list_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "Internal, primary database key.");

    /**
     * The column <code>oagi.code_list.guid</code>. GUID of the code list. Per OAGIS, a GUID is of the form "oagis-id-" followed by a 32 Hex character sequence.
     */
    public final TableField<CodeListRecord, String> GUID = createField(DSL.name("guid"), org.jooq.impl.SQLDataType.VARCHAR(41).nullable(false), this, "GUID of the code list. Per OAGIS, a GUID is of the form \"oagis-id-\" followed by a 32 Hex character sequence.");

    /**
     * The column <code>oagi.code_list.enum_type_guid</code>. In the OAGIS Model XML schema, a type, which keeps all the enumerated values, is  defined separately from the type that represents a code list. This only applies to some code lists. When that is the case, this column stores the GUID of that enumeration type.
     */
    public final TableField<CodeListRecord, String> ENUM_TYPE_GUID = createField(DSL.name("enum_type_guid"), org.jooq.impl.SQLDataType.VARCHAR(41), this, "In the OAGIS Model XML schema, a type, which keeps all the enumerated values, is  defined separately from the type that represents a code list. This only applies to some code lists. When that is the case, this column stores the GUID of that enumeration type.");

    /**
     * The column <code>oagi.code_list.name</code>. Name of the code list.
     */
    public final TableField<CodeListRecord, String> NAME = createField(DSL.name("name"), org.jooq.impl.SQLDataType.VARCHAR(100), this, "Name of the code list.");

    /**
     * The column <code>oagi.code_list.list_id</code>. External identifier.
     */
    public final TableField<CodeListRecord, String> LIST_ID = createField(DSL.name("list_id"), org.jooq.impl.SQLDataType.VARCHAR(100).nullable(false), this, "External identifier.");

    /**
     * The column <code>oagi.code_list.agency_id</code>. Foreign key to the AGENCY_ID_LIST_VALUE table. It indicates the organization which maintains the code list.
     */
    public final TableField<CodeListRecord, ULong> AGENCY_ID = createField(DSL.name("agency_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "Foreign key to the AGENCY_ID_LIST_VALUE table. It indicates the organization which maintains the code list.");

    /**
     * The column <code>oagi.code_list.version_id</code>. Code list version number.
     */
    public final TableField<CodeListRecord, String> VERSION_ID = createField(DSL.name("version_id"), org.jooq.impl.SQLDataType.VARCHAR(10).nullable(false), this, "Code list version number.");

    /**
     * The column <code>oagi.code_list.definition</code>. Description of the code list.
     */
    public final TableField<CodeListRecord, String> DEFINITION = createField(DSL.name("definition"), org.jooq.impl.SQLDataType.CLOB, this, "Description of the code list.");

    /**
     * The column <code>oagi.code_list.remark</code>. Usage information about the code list.
     */
    public final TableField<CodeListRecord, String> REMARK = createField(DSL.name("remark"), org.jooq.impl.SQLDataType.VARCHAR(225), this, "Usage information about the code list.");

    /**
     * The column <code>oagi.code_list.definition_source</code>. This is typically a URL which indicates the source of the code list's DEFINITION.
     */
    public final TableField<CodeListRecord, String> DEFINITION_SOURCE = createField(DSL.name("definition_source"), org.jooq.impl.SQLDataType.VARCHAR(100), this, "This is typically a URL which indicates the source of the code list's DEFINITION.");

    /**
     * The column <code>oagi.code_list.based_code_list_id</code>. This is a foreign key to the CODE_LIST table itself. This identifies the code list on which this code list is based, if any. The derivation may be restriction and/or extension.
     */
    public final TableField<CodeListRecord, ULong> BASED_CODE_LIST_ID = createField(DSL.name("based_code_list_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "This is a foreign key to the CODE_LIST table itself. This identifies the code list on which this code list is based, if any. The derivation may be restriction and/or extension.");

    /**
     * The column <code>oagi.code_list.extensible_indicator</code>. This is a flag to indicate whether the code list is final and shall not be further derived.
     */
    public final TableField<CodeListRecord, Byte> EXTENSIBLE_INDICATOR = createField(DSL.name("extensible_indicator"), org.jooq.impl.SQLDataType.TINYINT.nullable(false), this, "This is a flag to indicate whether the code list is final and shall not be further derived.");

    /**
     * The column <code>oagi.code_list.module_id</code>. Foreign key to the module table indicating the physical file the code list belongs to when generating a physical model schema. 
     */
    public final TableField<CodeListRecord, ULong> MODULE_ID = createField(DSL.name("module_id"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED, this, "Foreign key to the module table indicating the physical file the code list belongs to when generating a physical model schema. ");

    /**
     * The column <code>oagi.code_list.created_by</code>. Foreign key to the APP_USER table. It indicates the user who created the code list.
     */
    public final TableField<CodeListRecord, ULong> CREATED_BY = createField(DSL.name("created_by"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It indicates the user who created the code list.");

    /**
     * The column <code>oagi.code_list.last_updated_by</code>. Foreign key to the APP_USER table. It identifies the user who last updated the code list.
     */
    public final TableField<CodeListRecord, ULong> LAST_UPDATED_BY = createField(DSL.name("last_updated_by"), org.jooq.impl.SQLDataType.BIGINTUNSIGNED.nullable(false), this, "Foreign key to the APP_USER table. It identifies the user who last updated the code list.");

    /**
     * The column <code>oagi.code_list.creation_timestamp</code>. Timestamp when the code list was created.
     */
    public final TableField<CodeListRecord, Timestamp> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), org.jooq.impl.SQLDataType.TIMESTAMP.nullable(false), this, "Timestamp when the code list was created.");

    /**
     * The column <code>oagi.code_list.last_update_timestamp</code>. Timestamp when the code list was last updated.
     */
    public final TableField<CodeListRecord, Timestamp> LAST_UPDATE_TIMESTAMP = createField(DSL.name("last_update_timestamp"), org.jooq.impl.SQLDataType.TIMESTAMP.nullable(false), this, "Timestamp when the code list was last updated.");

    /**
     * The column <code>oagi.code_list.state</code>. Life cycle state of the code list. Possible values are Editing, Published, or Deleted. Only a code list in published state is available for derivation and for used by the CC and BIE. Once the code list is published, it cannot go back to Editing. A new version would have to be created.
     */
    public final TableField<CodeListRecord, String> STATE = createField(DSL.name("state"), org.jooq.impl.SQLDataType.VARCHAR(10), this, "Life cycle state of the code list. Possible values are Editing, Published, or Deleted. Only a code list in published state is available for derivation and for used by the CC and BIE. Once the code list is published, it cannot go back to Editing. A new version would have to be created.");

    /**
     * Create a <code>oagi.code_list</code> table reference
     */
    public CodeList() {
        this(DSL.name("code_list"), null);
    }

    /**
     * Create an aliased <code>oagi.code_list</code> table reference
     */
    public CodeList(String alias) {
        this(DSL.name(alias), CODE_LIST);
    }

    /**
     * Create an aliased <code>oagi.code_list</code> table reference
     */
    public CodeList(Name alias) {
        this(alias, CODE_LIST);
    }

    private CodeList(Name alias, Table<CodeListRecord> aliased) {
        this(alias, aliased, null);
    }

    private CodeList(Name alias, Table<CodeListRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment("This table stores information about a code list. When a code list is derived from another code list, the whole set of code values belonging to the based code list will be copied."));
    }

    public <O extends Record> CodeList(Table<O> child, ForeignKey<O, CodeListRecord> key) {
        super(child, key, CODE_LIST);
    }

    @Override
    public Schema getSchema() {
        return Oagi.OAGI;
    }

    @Override
    public List<Index> getIndexes() {
        return Arrays.<Index>asList(Indexes.CODE_LIST_CODE_LIST_AGENCY_ID_FK, Indexes.CODE_LIST_CODE_LIST_BASED_CODE_LIST_ID_FK, Indexes.CODE_LIST_CODE_LIST_CREATED_BY_FK, Indexes.CODE_LIST_CODE_LIST_LAST_UPDATED_BY_FK, Indexes.CODE_LIST_CODE_LIST_MODULE_ID_FK, Indexes.CODE_LIST_CODE_LIST_UK1, Indexes.CODE_LIST_CODE_LIST_UK2, Indexes.CODE_LIST_PRIMARY);
    }

    @Override
    public Identity<CodeListRecord, ULong> getIdentity() {
        return Keys.IDENTITY_CODE_LIST;
    }

    @Override
    public UniqueKey<CodeListRecord> getPrimaryKey() {
        return Keys.KEY_CODE_LIST_PRIMARY;
    }

    @Override
    public List<UniqueKey<CodeListRecord>> getKeys() {
        return Arrays.<UniqueKey<CodeListRecord>>asList(Keys.KEY_CODE_LIST_PRIMARY, Keys.KEY_CODE_LIST_CODE_LIST_UK1, Keys.KEY_CODE_LIST_CODE_LIST_UK2);
    }

    @Override
    public List<ForeignKey<CodeListRecord, ?>> getReferences() {
        return Arrays.<ForeignKey<CodeListRecord, ?>>asList(Keys.CODE_LIST_AGENCY_ID_FK, Keys.CODE_LIST_BASED_CODE_LIST_ID_FK, Keys.CODE_LIST_MODULE_ID_FK, Keys.CODE_LIST_CREATED_BY_FK, Keys.CODE_LIST_LAST_UPDATED_BY_FK);
    }

    public AgencyIdListValue agencyIdListValue() {
        return new AgencyIdListValue(this, Keys.CODE_LIST_AGENCY_ID_FK);
    }

    public org.oagi.srt.entity.jooq.tables.CodeList codeList() {
        return new org.oagi.srt.entity.jooq.tables.CodeList(this, Keys.CODE_LIST_BASED_CODE_LIST_ID_FK);
    }

    public Module module() {
        return new Module(this, Keys.CODE_LIST_MODULE_ID_FK);
    }

    public AppUser codeListCreatedByFk() {
        return new AppUser(this, Keys.CODE_LIST_CREATED_BY_FK);
    }

    public AppUser codeListLastUpdatedByFk() {
        return new AppUser(this, Keys.CODE_LIST_LAST_UPDATED_BY_FK);
    }

    @Override
    public CodeList as(String alias) {
        return new CodeList(DSL.name(alias), this);
    }

    @Override
    public CodeList as(Name alias) {
        return new CodeList(alias, this);
    }

    /**
     * Rename this table
     */
    @Override
    public CodeList rename(String name) {
        return new CodeList(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public CodeList rename(Name name) {
        return new CodeList(name, null);
    }

    // -------------------------------------------------------------------------
    // Row18 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row18<ULong, String, String, String, String, ULong, String, String, String, String, ULong, Byte, ULong, ULong, ULong, Timestamp, Timestamp, String> fieldsRow() {
        return (Row18) super.fieldsRow();
    }
}