/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables.records;


import org.jooq.Record1;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AgencyIdListValueManifest;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AgencyIdListValueManifestRecord extends UpdatableRecordImpl<AgencyIdListValueManifestRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_value_manifest_id</code>.
     */
    public void setAgencyIdListValueManifestId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_value_manifest_id</code>.
     */
    public ULong getAgencyIdListValueManifestId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.agency_id_list_value_manifest.release_id</code>.
     */
    public void setReleaseId(ULong value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_value_manifest.release_id</code>.
     */
    public ULong getReleaseId() {
        return (ULong) get(1);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_value_id</code>.
     */
    public void setAgencyIdListValueId(ULong value) {
        set(2, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_value_id</code>.
     */
    public ULong getAgencyIdListValueId() {
        return (ULong) get(2);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_manifest_id</code>.
     */
    public void setAgencyIdListManifestId(ULong value) {
        set(3, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.agency_id_list_manifest_id</code>.
     */
    public ULong getAgencyIdListManifestId() {
        return (ULong) get(3);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.based_agency_id_list_value_manifest_id</code>.
     */
    public void setBasedAgencyIdListValueManifestId(ULong value) {
        set(4, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.based_agency_id_list_value_manifest_id</code>.
     */
    public ULong getBasedAgencyIdListValueManifestId() {
        return (ULong) get(4);
    }

    /**
     * Setter for <code>oagi.agency_id_list_value_manifest.conflict</code>. This
     * indicates that there is a conflict between self and relationship.
     */
    public void setConflict(Byte value) {
        set(5, value);
    }

    /**
     * Getter for <code>oagi.agency_id_list_value_manifest.conflict</code>. This
     * indicates that there is a conflict between self and relationship.
     */
    public Byte getConflict() {
        return (Byte) get(5);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.replacement_agency_id_list_value_manifest_id</code>.
     * This refers to a replacement manifest if the record is deprecated.
     */
    public void setReplacementAgencyIdListValueManifestId(ULong value) {
        set(6, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.replacement_agency_id_list_value_manifest_id</code>.
     * This refers to a replacement manifest if the record is deprecated.
     */
    public ULong getReplacementAgencyIdListValueManifestId() {
        return (ULong) get(6);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.prev_agency_id_list_value_manifest_id</code>.
     */
    public void setPrevAgencyIdListValueManifestId(ULong value) {
        set(7, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.prev_agency_id_list_value_manifest_id</code>.
     */
    public ULong getPrevAgencyIdListValueManifestId() {
        return (ULong) get(7);
    }

    /**
     * Setter for
     * <code>oagi.agency_id_list_value_manifest.next_agency_id_list_value_manifest_id</code>.
     */
    public void setNextAgencyIdListValueManifestId(ULong value) {
        set(8, value);
    }

    /**
     * Getter for
     * <code>oagi.agency_id_list_value_manifest.next_agency_id_list_value_manifest_id</code>.
     */
    public ULong getNextAgencyIdListValueManifestId() {
        return (ULong) get(8);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached AgencyIdListValueManifestRecord
     */
    public AgencyIdListValueManifestRecord() {
        super(AgencyIdListValueManifest.AGENCY_ID_LIST_VALUE_MANIFEST);
    }

    /**
     * Create a detached, initialised AgencyIdListValueManifestRecord
     */
    public AgencyIdListValueManifestRecord(ULong agencyIdListValueManifestId, ULong releaseId, ULong agencyIdListValueId, ULong agencyIdListManifestId, ULong basedAgencyIdListValueManifestId, Byte conflict, ULong replacementAgencyIdListValueManifestId, ULong prevAgencyIdListValueManifestId, ULong nextAgencyIdListValueManifestId) {
        super(AgencyIdListValueManifest.AGENCY_ID_LIST_VALUE_MANIFEST);

        setAgencyIdListValueManifestId(agencyIdListValueManifestId);
        setReleaseId(releaseId);
        setAgencyIdListValueId(agencyIdListValueId);
        setAgencyIdListManifestId(agencyIdListManifestId);
        setBasedAgencyIdListValueManifestId(basedAgencyIdListValueManifestId);
        setConflict(conflict);
        setReplacementAgencyIdListValueManifestId(replacementAgencyIdListValueManifestId);
        setPrevAgencyIdListValueManifestId(prevAgencyIdListValueManifestId);
        setNextAgencyIdListValueManifestId(nextAgencyIdListValueManifestId);
        resetChangedOnNotNull();
    }
}
