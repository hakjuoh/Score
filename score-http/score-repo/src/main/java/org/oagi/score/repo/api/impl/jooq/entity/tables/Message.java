/*
 * This file is generated by jOOQ.
 */
package org.oagi.score.repo.api.impl.jooq.entity.tables;


import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Identity;
import org.jooq.InverseForeignKey;
import org.jooq.Name;
import org.jooq.Path;
import org.jooq.PlainSQL;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.SQL;
import org.jooq.Schema;
import org.jooq.Select;
import org.jooq.Stringly;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.types.ULong;
import org.oagi.score.repo.api.impl.jooq.entity.Keys;
import org.oagi.score.repo.api.impl.jooq.entity.Oagi;
import org.oagi.score.repo.api.impl.jooq.entity.tables.AppUser.AppUserPath;
import org.oagi.score.repo.api.impl.jooq.entity.tables.records.MessageRecord;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Message extends TableImpl<MessageRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>oagi.message</code>
     */
    public static final Message MESSAGE = new Message();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<MessageRecord> getRecordType() {
        return MessageRecord.class;
    }

    /**
     * The column <code>oagi.message.message_id</code>.
     */
    public final TableField<MessageRecord, ULong> MESSAGE_ID = createField(DSL.name("message_id"), SQLDataType.BIGINTUNSIGNED.nullable(false).identity(true), this, "");

    /**
     * The column <code>oagi.message.sender_id</code>. The user who created this
     * record.
     */
    public final TableField<MessageRecord, ULong> SENDER_ID = createField(DSL.name("sender_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who created this record.");

    /**
     * The column <code>oagi.message.recipient_id</code>. The user who is a
     * target to possess this record.
     */
    public final TableField<MessageRecord, ULong> RECIPIENT_ID = createField(DSL.name("recipient_id"), SQLDataType.BIGINTUNSIGNED.nullable(false), this, "The user who is a target to possess this record.");

    /**
     * The column <code>oagi.message.subject</code>. A subject of the message
     */
    public final TableField<MessageRecord, String> SUBJECT = createField(DSL.name("subject"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "A subject of the message");

    /**
     * The column <code>oagi.message.body</code>. A body of the message.
     */
    public final TableField<MessageRecord, String> BODY = createField(DSL.name("body"), SQLDataType.CLOB.defaultValue(DSL.field(DSL.raw("NULL"), SQLDataType.CLOB)), this, "A body of the message.");

    /**
     * The column <code>oagi.message.body_content_type</code>. A content type of
     * the body
     */
    public final TableField<MessageRecord, String> BODY_CONTENT_TYPE = createField(DSL.name("body_content_type"), SQLDataType.VARCHAR(50).nullable(false).defaultValue(DSL.field(DSL.raw("'text/plain'"), SQLDataType.VARCHAR)), this, "A content type of the body");

    /**
     * The column <code>oagi.message.is_read</code>. An indicator whether this
     * record is read or not.
     */
    public final TableField<MessageRecord, Byte> IS_READ = createField(DSL.name("is_read"), SQLDataType.TINYINT.defaultValue(DSL.field(DSL.raw("0"), SQLDataType.TINYINT)), this, "An indicator whether this record is read or not.");

    /**
     * The column <code>oagi.message.creation_timestamp</code>. The timestamp
     * when the record was first created.
     */
    public final TableField<MessageRecord, LocalDateTime> CREATION_TIMESTAMP = createField(DSL.name("creation_timestamp"), SQLDataType.LOCALDATETIME(6).nullable(false), this, "The timestamp when the record was first created.");

    private Message(Name alias, Table<MessageRecord> aliased) {
        this(alias, aliased, (Field<?>[]) null, null);
    }

    private Message(Name alias, Table<MessageRecord> aliased, Field<?>[] parameters, Condition where) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table(), where);
    }

    /**
     * Create an aliased <code>oagi.message</code> table reference
     */
    public Message(String alias) {
        this(DSL.name(alias), MESSAGE);
    }

    /**
     * Create an aliased <code>oagi.message</code> table reference
     */
    public Message(Name alias) {
        this(alias, MESSAGE);
    }

    /**
     * Create a <code>oagi.message</code> table reference
     */
    public Message() {
        this(DSL.name("message"), null);
    }

    public <O extends Record> Message(Table<O> path, ForeignKey<O, MessageRecord> childPath, InverseForeignKey<O, MessageRecord> parentPath) {
        super(path, childPath, parentPath, MESSAGE);
    }

    /**
     * A subtype implementing {@link Path} for simplified path-based joins.
     */
    public static class MessagePath extends Message implements Path<MessageRecord> {
        public <O extends Record> MessagePath(Table<O> path, ForeignKey<O, MessageRecord> childPath, InverseForeignKey<O, MessageRecord> parentPath) {
            super(path, childPath, parentPath);
        }
        private MessagePath(Name alias, Table<MessageRecord> aliased) {
            super(alias, aliased);
        }

        @Override
        public MessagePath as(String alias) {
            return new MessagePath(DSL.name(alias), this);
        }

        @Override
        public MessagePath as(Name alias) {
            return new MessagePath(alias, this);
        }

        @Override
        public MessagePath as(Table<?> alias) {
            return new MessagePath(alias.getQualifiedName(), this);
        }
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Oagi.OAGI;
    }

    @Override
    public Identity<MessageRecord, ULong> getIdentity() {
        return (Identity<MessageRecord, ULong>) super.getIdentity();
    }

    @Override
    public UniqueKey<MessageRecord> getPrimaryKey() {
        return Keys.KEY_MESSAGE_PRIMARY;
    }

    @Override
    public List<ForeignKey<MessageRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MESSAGE_SENDER_ID_FK, Keys.MESSAGE_RECIPIENT_ID_FK);
    }

    private transient AppUserPath _messageSenderIdFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>message_sender_id_fk</code> key.
     */
    public AppUserPath messageSenderIdFk() {
        if (_messageSenderIdFk == null)
            _messageSenderIdFk = new AppUserPath(this, Keys.MESSAGE_SENDER_ID_FK, null);

        return _messageSenderIdFk;
    }

    private transient AppUserPath _messageRecipientIdFk;

    /**
     * Get the implicit join path to the <code>oagi.app_user</code> table, via
     * the <code>message_recipient_id_fk</code> key.
     */
    public AppUserPath messageRecipientIdFk() {
        if (_messageRecipientIdFk == null)
            _messageRecipientIdFk = new AppUserPath(this, Keys.MESSAGE_RECIPIENT_ID_FK, null);

        return _messageRecipientIdFk;
    }

    @Override
    public Message as(String alias) {
        return new Message(DSL.name(alias), this);
    }

    @Override
    public Message as(Name alias) {
        return new Message(alias, this);
    }

    @Override
    public Message as(Table<?> alias) {
        return new Message(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public Message rename(String name) {
        return new Message(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public Message rename(Name name) {
        return new Message(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public Message rename(Table<?> name) {
        return new Message(name.getQualifiedName(), null);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message where(Condition condition) {
        return new Message(getQualifiedName(), aliased() ? this : null, null, condition);
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message where(Collection<? extends Condition> conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message where(Condition... conditions) {
        return where(DSL.and(conditions));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message where(Field<Boolean> condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Message where(SQL condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Message where(@Stringly.SQL String condition) {
        return where(DSL.condition(condition));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Message where(@Stringly.SQL String condition, Object... binds) {
        return where(DSL.condition(condition, binds));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    @PlainSQL
    public Message where(@Stringly.SQL String condition, QueryPart... parts) {
        return where(DSL.condition(condition, parts));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message whereExists(Select<?> select) {
        return where(DSL.exists(select));
    }

    /**
     * Create an inline derived table from this table
     */
    @Override
    public Message whereNotExists(Select<?> select) {
        return where(DSL.notExists(select));
    }
}
