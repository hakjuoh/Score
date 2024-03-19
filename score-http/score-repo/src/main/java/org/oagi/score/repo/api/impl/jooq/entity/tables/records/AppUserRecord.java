/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables.records;


import org.jooq.Record1;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AppUser;


/**
 * This table captures the user information for authentication and authorization
 * purposes.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class AppUserRecord extends UpdatableRecordImpl<AppUserRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>oagi.app_user.app_user_id</code>. Primary key column.
     */
    public void setAppUserId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for <code>oagi.app_user.app_user_id</code>. Primary key column.
     */
    public ULong getAppUserId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.app_user.login_id</code>. User Id of the user.
     */
    public void setLoginId(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.app_user.login_id</code>. User Id of the user.
     */
    public String getLoginId() {
        return (String) get(1);
    }

    /**
     * Setter for <code>oagi.app_user.password</code>. Password to authenticate
     * the user.
     */
    public void setPassword(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.app_user.password</code>. Password to authenticate
     * the user.
     */
    public String getPassword() {
        return (String) get(2);
    }

    /**
     * Setter for <code>oagi.app_user.name</code>. Full name of the user.
     */
    public void setName(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>oagi.app_user.name</code>. Full name of the user.
     */
    public String getName() {
        return (String) get(3);
    }

    /**
     * Setter for <code>oagi.app_user.organization</code>. The company the user
     * represents.
     */
    public void setOrganization(String value) {
        set(4, value);
    }

    /**
     * Getter for <code>oagi.app_user.organization</code>. The company the user
     * represents.
     */
    public String getOrganization() {
        return (String) get(4);
    }

    /**
     * Setter for <code>oagi.app_user.email</code>. Email address.
     */
    public void setEmail(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>oagi.app_user.email</code>. Email address.
     */
    public String getEmail() {
        return (String) get(5);
    }

    /**
     * Setter for <code>oagi.app_user.email_verified</code>. The fact whether
     * the email value is verified or not.
     */
    public void setEmailVerified(Byte value) {
        set(6, value);
    }

    /**
     * Getter for <code>oagi.app_user.email_verified</code>. The fact whether
     * the email value is verified or not.
     */
    public Byte getEmailVerified() {
        return (Byte) get(6);
    }

    /**
     * Setter for <code>oagi.app_user.is_developer</code>.
     */
    public void setIsDeveloper(Byte value) {
        set(7, value);
    }

    /**
     * Getter for <code>oagi.app_user.is_developer</code>.
     */
    public Byte getIsDeveloper() {
        return (Byte) get(7);
    }

    /**
     * Setter for <code>oagi.app_user.is_admin</code>. Indicator whether the
     * user has an admin role or not.
     */
    public void setIsAdmin(Byte value) {
        set(8, value);
    }

    /**
     * Getter for <code>oagi.app_user.is_admin</code>. Indicator whether the
     * user has an admin role or not.
     */
    public Byte getIsAdmin() {
        return (Byte) get(8);
    }

    /**
     * Setter for <code>oagi.app_user.is_enabled</code>.
     */
    public void setIsEnabled(Byte value) {
        set(9, value);
    }

    /**
     * Getter for <code>oagi.app_user.is_enabled</code>.
     */
    public Byte getIsEnabled() {
        return (Byte) get(9);
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
     * Create a detached AppUserRecord
     */
    public AppUserRecord() {
        super(AppUser.APP_USER);
    }

    /**
     * Create a detached, initialised AppUserRecord
     */
    public AppUserRecord(ULong appUserId, String loginId, String password, String name, String organization, String email, Byte emailVerified, Byte isDeveloper, Byte isAdmin, Byte isEnabled) {
        super(AppUser.APP_USER);

        setAppUserId(appUserId);
        setLoginId(loginId);
        setPassword(password);
        setName(name);
        setOrganization(organization);
        setEmail(email);
        setEmailVerified(emailVerified);
        setIsDeveloper(isDeveloper);
        setIsAdmin(isAdmin);
        setIsEnabled(isEnabled);
        resetChangedOnNotNull();
    }
}
