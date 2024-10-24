/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables.records;


import java.time.LocalDateTime;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record9;
import org.jooq.Row9;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.tables.Comment;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class CommentRecord extends UpdatableRecordImpl<CommentRecord> implements Record9<ULong, String, String, Byte, Byte, ULong, ULong, LocalDateTime, LocalDateTime> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>oagi.comment.comment_id</code>.
     */
    public void setCommentId(ULong value) {
        set(0, value);
    }

    /**
     * Getter for <code>oagi.comment.comment_id</code>.
     */
    public ULong getCommentId() {
        return (ULong) get(0);
    }

    /**
     * Setter for <code>oagi.comment.reference</code>.
     */
    public void setReference(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>oagi.comment.reference</code>.
     */
    public String getReference() {
        return (String) get(1);
    }

    /**
     * Setter for <code>oagi.comment.comment</code>.
     */
    public void setComment(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>oagi.comment.comment</code>.
     */
    public String getComment() {
        return (String) get(2);
    }

    /**
     * Setter for <code>oagi.comment.is_hidden</code>.
     */
    public void setIsHidden(Byte value) {
        set(3, value);
    }

    /**
     * Getter for <code>oagi.comment.is_hidden</code>.
     */
    public Byte getIsHidden() {
        return (Byte) get(3);
    }

    /**
     * Setter for <code>oagi.comment.is_deleted</code>.
     */
    public void setIsDeleted(Byte value) {
        set(4, value);
    }

    /**
     * Getter for <code>oagi.comment.is_deleted</code>.
     */
    public Byte getIsDeleted() {
        return (Byte) get(4);
    }

    /**
     * Setter for <code>oagi.comment.prev_comment_id</code>.
     */
    public void setPrevCommentId(ULong value) {
        set(5, value);
    }

    /**
     * Getter for <code>oagi.comment.prev_comment_id</code>.
     */
    public ULong getPrevCommentId() {
        return (ULong) get(5);
    }

    /**
     * Setter for <code>oagi.comment.created_by</code>.
     */
    public void setCreatedBy(ULong value) {
        set(6, value);
    }

    /**
     * Getter for <code>oagi.comment.created_by</code>.
     */
    public ULong getCreatedBy() {
        return (ULong) get(6);
    }

    /**
     * Setter for <code>oagi.comment.creation_timestamp</code>.
     */
    public void setCreationTimestamp(LocalDateTime value) {
        set(7, value);
    }

    /**
     * Getter for <code>oagi.comment.creation_timestamp</code>.
     */
    public LocalDateTime getCreationTimestamp() {
        return (LocalDateTime) get(7);
    }

    /**
     * Setter for <code>oagi.comment.last_update_timestamp</code>.
     */
    public void setLastUpdateTimestamp(LocalDateTime value) {
        set(8, value);
    }

    /**
     * Getter for <code>oagi.comment.last_update_timestamp</code>.
     */
    public LocalDateTime getLastUpdateTimestamp() {
        return (LocalDateTime) get(8);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<ULong> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record9 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row9<ULong, String, String, Byte, Byte, ULong, ULong, LocalDateTime, LocalDateTime> fieldsRow() {
        return (Row9) super.fieldsRow();
    }

    @Override
    public Row9<ULong, String, String, Byte, Byte, ULong, ULong, LocalDateTime, LocalDateTime> valuesRow() {
        return (Row9) super.valuesRow();
    }

    @Override
    public Field<ULong> field1() {
        return Comment.COMMENT.COMMENT_ID;
    }

    @Override
    public Field<String> field2() {
        return Comment.COMMENT.REFERENCE;
    }

    @Override
    public Field<String> field3() {
        return Comment.COMMENT.COMMENT_;
    }

    @Override
    public Field<Byte> field4() {
        return Comment.COMMENT.IS_HIDDEN;
    }

    @Override
    public Field<Byte> field5() {
        return Comment.COMMENT.IS_DELETED;
    }

    @Override
    public Field<ULong> field6() {
        return Comment.COMMENT.PREV_COMMENT_ID;
    }

    @Override
    public Field<ULong> field7() {
        return Comment.COMMENT.CREATED_BY;
    }

    @Override
    public Field<LocalDateTime> field8() {
        return Comment.COMMENT.CREATION_TIMESTAMP;
    }

    @Override
    public Field<LocalDateTime> field9() {
        return Comment.COMMENT.LAST_UPDATE_TIMESTAMP;
    }

    @Override
    public ULong component1() {
        return getCommentId();
    }

    @Override
    public String component2() {
        return getReference();
    }

    @Override
    public String component3() {
        return getComment();
    }

    @Override
    public Byte component4() {
        return getIsHidden();
    }

    @Override
    public Byte component5() {
        return getIsDeleted();
    }

    @Override
    public ULong component6() {
        return getPrevCommentId();
    }

    @Override
    public ULong component7() {
        return getCreatedBy();
    }

    @Override
    public LocalDateTime component8() {
        return getCreationTimestamp();
    }

    @Override
    public LocalDateTime component9() {
        return getLastUpdateTimestamp();
    }

    @Override
    public ULong value1() {
        return getCommentId();
    }

    @Override
    public String value2() {
        return getReference();
    }

    @Override
    public String value3() {
        return getComment();
    }

    @Override
    public Byte value4() {
        return getIsHidden();
    }

    @Override
    public Byte value5() {
        return getIsDeleted();
    }

    @Override
    public ULong value6() {
        return getPrevCommentId();
    }

    @Override
    public ULong value7() {
        return getCreatedBy();
    }

    @Override
    public LocalDateTime value8() {
        return getCreationTimestamp();
    }

    @Override
    public LocalDateTime value9() {
        return getLastUpdateTimestamp();
    }

    @Override
    public CommentRecord value1(ULong value) {
        setCommentId(value);
        return this;
    }

    @Override
    public CommentRecord value2(String value) {
        setReference(value);
        return this;
    }

    @Override
    public CommentRecord value3(String value) {
        setComment(value);
        return this;
    }

    @Override
    public CommentRecord value4(Byte value) {
        setIsHidden(value);
        return this;
    }

    @Override
    public CommentRecord value5(Byte value) {
        setIsDeleted(value);
        return this;
    }

    @Override
    public CommentRecord value6(ULong value) {
        setPrevCommentId(value);
        return this;
    }

    @Override
    public CommentRecord value7(ULong value) {
        setCreatedBy(value);
        return this;
    }

    @Override
    public CommentRecord value8(LocalDateTime value) {
        setCreationTimestamp(value);
        return this;
    }

    @Override
    public CommentRecord value9(LocalDateTime value) {
        setLastUpdateTimestamp(value);
        return this;
    }

    @Override
    public CommentRecord values(ULong value1, String value2, String value3, Byte value4, Byte value5, ULong value6, ULong value7, LocalDateTime value8, LocalDateTime value9) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        value8(value8);
        value9(value9);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached CommentRecord
     */
    public CommentRecord() {
        super(Comment.COMMENT);
    }

    /**
     * Create a detached, initialised CommentRecord
     */
    public CommentRecord(ULong commentId, String reference, String comment, Byte isHidden, Byte isDeleted, ULong prevCommentId, ULong createdBy, LocalDateTime creationTimestamp, LocalDateTime lastUpdateTimestamp) {
        super(Comment.COMMENT);

        setCommentId(commentId);
        setReference(reference);
        setComment(comment);
        setIsHidden(isHidden);
        setIsDeleted(isDeleted);
        setPrevCommentId(prevCommentId);
        setCreatedBy(createdBy);
        setCreationTimestamp(creationTimestamp);
        setLastUpdateTimestamp(lastUpdateTimestamp);
        resetChangedOnNotNull();
    }
}
