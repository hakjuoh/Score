/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.e2e.impl.api.jooq.entity.tables.records;


import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record10;
import org.jooq.Row10;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.e2e.impl.api.jooq.entity.tables.AgencyIdListManifest;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AgencyIdListManifestRecord extends UpdatableRecordImpl<AgencyIdListManifestRecord> implements Record10<ULong, ULong, ULong, ULong, ULong, Byte, ULong, ULong, ULong, ULong> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.agency_id_list_manifest_id</code>.
     */
    public void setAgencyIdListManifestId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.agency_id_list_manifest_id</code>.
     */
    public ULong getAgencyIdListManifestId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.agency_id_list_manifest.release_id</code>.
     */
    public void setReleaseId(ULong value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_manifest.release_id</code>.
     */
    public ULong getReleaseId() {
        return (ULong) get(1);
    }

    /**
     * Setter for <code>oagi.agency_id_list_manifest.agency_id_list_id</code>.
     */
    public void setAgencyIdListId(ULong value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_manifest.agency_id_list_id</code>.
     */
    public ULong getAgencyIdListId() {
        return (ULong) get(2);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.agency_id_list_value_manifest_id</code>.
     */
    public void setAgencyIdListValueManifestId(ULong value) {
        set(3, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.agency_id_list_value_manifest_id</code>.
     */
    public ULong getAgencyIdListValueManifestId() {
        return (ULong) get(3);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.based_agency_id_list_manifest_id</code>.
     */
    public void setBasedAgencyIdListManifestId(ULong value) {
        set(4, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.based_agency_id_list_manifest_id</code>.
     */
    public ULong getBasedAgencyIdListManifestId() {
        return (ULong) get(4);
    }

    /**
     * Setter for <code>oagi.agency_id_list_manifest.conflict</code>. This
     * indicates that there is a conflict between self and relationship.
     */
    public void setConflict(Byte value) {
        set(5, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_manifest.conflict</code>. This
     * indicates that there is a conflict between self and relationship.
     */
    public Byte getConflict() {
        return (Byte) get(5);
    }

    /**
     * Setter for <code>oagi.agency_id_list_manifest.log_id</code>. A foreign
     * key pointed to a log for the current record.
     */
    public void setLogId(ULong value) {
        set(6, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_manifest.log_id</code>. A foreign
     * key pointed to a log for the current record.
     */
    public ULong getLogId() {
        return (ULong) get(6);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.replacement_agency_id_list_manifest_id</code>.
     * This refers to a replacement manifest if the record is deprecated.
     */
    public void setReplacementAgencyIdListManifestId(ULong value) {
        set(7, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.replacement_agency_id_list_manifest_id</code>.
     * This refers to a replacement manifest if the record is deprecated.
     */
    public ULong getReplacementAgencyIdListManifestId() {
        return (ULong) get(7);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.prev_agency_id_list_manifest_id</code>.
     */
    public void setPrevAgencyIdListManifestId(ULong value) {
        set(8, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.prev_agency_id_list_manifest_id</code>.
     */
    public ULong getPrevAgencyIdListManifestId() {
        return (ULong) get(8);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_manifest.next_agency_id_list_manifest_id</code>.
     */
    public void setNextAgencyIdListManifestId(ULong value) {
        set(9, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_manifest.next_agency_id_list_manifest_id</code>.
     */
    public ULong getNextAgencyIdListManifestId() {
        return (ULong) get(9);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record10 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row10<ULong, ULong, ULong, ULong, ULong, Byte, ULong, ULong, ULong, ULong> fieldsRow() {
        return (Row10) super.fieldsRow();
    }

    @Override
    public Row10<ULong, ULong, ULong, ULong, ULong, Byte, ULong, ULong, ULong, ULong> valuesRow() {
        return (Row10) super.valuesRow();
    }

    @Override
    public Field<ULong> field1() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_MANIFEST_ID;
    }

    @Override
    public Field<ULong> field2() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.RELEASE_ID;
    }

    @Override
    public Field<ULong> field3() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_ID;
    }

    @Override
    public Field<ULong> field4() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.AGENCY_ID_LIST_VALUE_MANIFEST_ID;
    }

    @Override
    public Field<ULong> field5() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.BASED_AGENCY_ID_LIST_MANIFEST_ID;
    }

    @Override
    public Field<Byte> field6() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.CONFLICT;
    }

    @Override
    public Field<ULong> field7() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.LOG_ID;
    }

    @Override
    public Field<ULong> field8() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.REPLACEMENT_AGENCY_ID_LIST_MANIFEST_ID;
    }

    @Override
    public Field<ULong> field9() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.PREV_AGENCY_ID_LIST_MANIFEST_ID;
    }

    @Override
    public Field<ULong> field10() {
        return AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST.NEXT_AGENCY_ID_LIST_MANIFEST_ID;
    }

    @Override
    public ULong component1() {
        return getAgencyIdListManifestId();
    }

    @Override
    public ULong component2() {
        return getReleaseId();
    }

    @Override
    public ULong component3() {
        return getAgencyIdListId();
    }

    @Override
    public ULong component4() {
        return getAgencyIdListValueManifestId();
    }

    @Override
    public ULong component5() {
        return getBasedAgencyIdListManifestId();
    }

    @Override
    public Byte component6() {
        return getConflict();
    }

    @Override
    public ULong component7() {
        return getLogId();
    }

    @Override
    public ULong component8() {
        return getReplacementAgencyIdListManifestId();
    }

    @Override
    public ULong component9() {
        return getPrevAgencyIdListManifestId();
    }

    @Override
    public ULong component10() {
        return getNextAgencyIdListManifestId();
    }

    @Override
    public ULong value1() {
        return getAgencyIdListManifestId();
    }

    @Override
    public ULong value2() {
        return getReleaseId();
    }

    @Override
    public ULong value3() {
        return getAgencyIdListId();
    }

    @Override
    public ULong value4() {
        return getAgencyIdListValueManifestId();
    }

    @Override
    public ULong value5() {
        return getBasedAgencyIdListManifestId();
    }

    @Override
    public Byte value6() {
        return getConflict();
    }

    @Override
    public ULong value7() {
        return getLogId();
    }

    @Override
    public ULong value8() {
        return getReplacementAgencyIdListManifestId();
    }

    @Override
    public ULong value9() {
        return getPrevAgencyIdListManifestId();
    }

    @Override
    public ULong value10() {
        return getNextAgencyIdListManifestId();
    }

    @Override
    public AgencyIdListManifestRecord value1(ULong value) {
        setAgencyIdListManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value2(ULong value) {
        setReleaseId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value3(ULong value) {
        setAgencyIdListId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value4(ULong value) {
        setAgencyIdListValueManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value5(ULong value) {
        setBasedAgencyIdListManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value6(Byte value) {
        setConflict(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value7(ULong value) {
        setLogId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value8(ULong value) {
        setReplacementAgencyIdListManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value9(ULong value) {
        setPrevAgencyIdListManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord value10(ULong value) {
        setNextAgencyIdListManifestId(value);
        return this;
    }

    @Override
    public AgencyIdListManifestRecord values(ULong value1, ULong value2, ULong value3, ULong value4, ULong value5, Byte value6, ULong value7, ULong value8, ULong value9, ULong value10) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        value8(value8);
        value9(value9);
        value10(value10);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached AgencyIdListManifestRecord
     */
    public AgencyIdListManifestRecord() {
        super(AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST);
    }

    /**
     * Create a detached, initialised AgencyIdListManifestRecord
     */
    public AgencyIdListManifestRecord(ULong agencyIdListManifestId, ULong releaseId, ULong agencyIdListId, ULong agencyIdListValueManifestId, ULong basedAgencyIdListManifestId, Byte conflict, ULong logId, ULong replacementAgencyIdListManifestId, ULong prevAgencyIdListManifestId, ULong nextAgencyIdListManifestId) {
        super(AgencyIdListManifest.AGENCY_ID_LIST_MANIFEST);

        setAgencyIdListManifestId(agencyIdListManifestId);
        setReleaseId(releaseId);
        setAgencyIdListId(agencyIdListId);
        setAgencyIdListValueManifestId(agencyIdListValueManifestId);
        setBasedAgencyIdListManifestId(basedAgencyIdListManifestId);
        setConflict(conflict);
        setLogId(logId);
        setReplacementAgencyIdListManifestId(replacementAgencyIdListManifestId);
        setPrevAgencyIdListManifestId(prevAgencyIdListManifestId);
        setNextAgencyIdListManifestId(nextAgencyIdListManifestId);
    }
}