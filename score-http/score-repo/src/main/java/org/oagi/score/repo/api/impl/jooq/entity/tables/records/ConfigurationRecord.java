/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record4;
import org.jooq.Row4;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Configuration;


/**
 * The table stores configuration properties of the application.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ConfigurationRecord extends UpdatableRecordImpl<ConfigurationRecord> implements Record4<ULong, String, String, String> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>oagi.configuration.configuration_id</code>. Primary key
     * column.
     */
    public void setConfigurationId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for <code>oagi.configuration.configuration_id</code>. Primary key
     * column.
     */
    public ULong getConfigurationId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.configuration.name</code>. The name of
     * configuration property.
     */
    public void setName(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.configuration.name</code>. The name of
     * configuration property.
     */
    public String getName() {
        return (String) get(1);
    }

    /**
     * Setter for <code>oagi.configuration.type</code>. The type of
     * configuration property.
     */
    public void setType(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.configuration.type</code>. The type of
     * configuration property.
     */
    public String getType() {
        return (String) get(2);
    }

    /**
     * Setter for <code>oagi.configuration.value</code>. The value of
     * configuration property.
     */
    public void setValue(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>oagi.configuration.value</code>. The value of
     * configuration property.
     */
    public String getValue() {
        return (String) get(3);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record4 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row4<ULong, String, String, String> fieldsRow() {
        return (Row4) super.fieldsRow();
    }

    @Override
    public Row4<ULong, String, String, String> valuesRow() {
        return (Row4) super.valuesRow();
    }

    @Override
    public Field<ULong> field1() {
        return Configuration.CONFIGURATION.CONFIGURATION_ID;
    }

    @Override
    public Field<String> field2() {
        return Configuration.CONFIGURATION.NAME;
    }

    @Override
    public Field<String> field3() {
        return Configuration.CONFIGURATION.TYPE;
    }

    @Override
    public Field<String> field4() {
        return Configuration.CONFIGURATION.VALUE;
    }

    @Override
    public ULong component1() {
        return getConfigurationId();
    }

    @Override
    public String component2() {
        return getName();
    }

    @Override
    public String component3() {
        return getType();
    }

    @Override
    public String component4() {
        return getValue();
    }

    @Override
    public ULong value1() {
        return getConfigurationId();
    }

    @Override
    public String value2() {
        return getName();
    }

    @Override
    public String value3() {
        return getType();
    }

    @Override
    public String value4() {
        return getValue();
    }

    @Override
    public ConfigurationRecord value1(ULong value) {
        setConfigurationId(value);
        return this;
    }

    @Override
    public ConfigurationRecord value2(String value) {
        setName(value);
        return this;
    }

    @Override
    public ConfigurationRecord value3(String value) {
        setType(value);
        return this;
    }

    @Override
    public ConfigurationRecord value4(String value) {
        setValue(value);
        return this;
    }

    @Override
    public ConfigurationRecord values(ULong value1, String value2, String value3, String value4) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached ConfigurationRecord
     */
    public ConfigurationRecord() {
        super(Configuration.CONFIGURATION);
    }

    /**
     * Create a detached, initialised ConfigurationRecord
     */
    public ConfigurationRecord(ULong configurationId, String name, String type, String value) {
        super(Configuration.CONFIGURATION);

        setConfigurationId(configurationId);
        setName(name);
        setType(type);
        setValue(value);
        resetChangedOnNotNull();
    }
}
