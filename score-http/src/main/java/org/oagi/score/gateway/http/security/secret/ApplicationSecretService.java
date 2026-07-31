package org.oagi.score.gateway.http.security.secret;

import org.jooq.DSLContext;
import org.jooq.types.ULong;
import org.jooq.types.UShort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.oagi.score.gateway.http.common.repository.jooq.entity.Tables.APP_SECRET;

/** Internal encrypted-secret storage. No generic secret HTTP API is exposed. */
@Service
public class ApplicationSecretService {

    public static final String AI_PROVIDER_API_KEY = "AI_PROVIDER_API_KEY";

    private final ApplicationSecretCrypto crypto;
    private final AiSecretObservability observability;

    public ApplicationSecretService(ApplicationSecretCrypto crypto,
                                    AiSecretObservability observability) {
        this.crypto = crypto;
        this.observability = observability;
    }

    /** Whether encrypted provider credentials can be created or opened in this process. */
    public boolean isEncryptionConfigured() {
        return crypto.isConfigured();
    }

    public ULong create(DSLContext tx, String name, char[] plaintext, ULong actorId) {
        String guid = UUID.randomUUID().toString();
        var context = new ApplicationSecretCrypto.SecretContext(
                guid, name, AI_PROVIDER_API_KEY);
        var encrypted = crypto.encrypt(plaintext, context);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return tx.insertInto(APP_SECRET)
                .set(APP_SECRET.SECRET_GUID, guid)
                .set(APP_SECRET.SECRET_NAME, name)
                .set(APP_SECRET.SECRET_TYPE, AI_PROVIDER_API_KEY)
                .set(APP_SECRET.ENCRYPTED_VALUE, encrypted.ciphertext())
                .set(APP_SECRET.NONCE, encrypted.nonce())
                .set(APP_SECRET.ENCRYPTION_KEY_ID, encrypted.keyId())
                .set(APP_SECRET.ENCRYPTION_VERSION, UShort.valueOf(encrypted.version()))
                .set(APP_SECRET.CREATED_BY, actorId)
                .set(APP_SECRET.LAST_UPDATED_BY, actorId)
                .set(APP_SECRET.CREATED_AT, now)
                .set(APP_SECRET.LAST_UPDATED_AT, now)
                .returning(APP_SECRET.APP_SECRET_ID)
                .fetchOne(APP_SECRET.APP_SECRET_ID);
    }

    public void replace(DSLContext tx, ULong secretId, char[] plaintext, ULong actorId) {
        var row = tx.select(APP_SECRET.SECRET_GUID, APP_SECRET.SECRET_NAME,
                        APP_SECRET.SECRET_TYPE)
                .from(APP_SECRET).where(APP_SECRET.APP_SECRET_ID.eq(secretId))
                .forUpdate().fetchOne();
        if (row == null) throw new IllegalStateException("The provider secret does not exist.");
        var context = new ApplicationSecretCrypto.SecretContext(
                row.get(APP_SECRET.SECRET_GUID), row.get(APP_SECRET.SECRET_NAME),
                row.get(APP_SECRET.SECRET_TYPE));
        var encrypted = crypto.encrypt(plaintext, context);
        tx.update(APP_SECRET)
                .set(APP_SECRET.ENCRYPTED_VALUE, encrypted.ciphertext())
                .set(APP_SECRET.NONCE, encrypted.nonce())
                .set(APP_SECRET.ENCRYPTION_KEY_ID, encrypted.keyId())
                .set(APP_SECRET.ENCRYPTION_VERSION, UShort.valueOf(encrypted.version()))
                .set(APP_SECRET.LAST_UPDATED_BY, actorId)
                .set(APP_SECRET.LAST_UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
                .where(APP_SECRET.APP_SECRET_ID.eq(secretId)).execute();
    }

    public char[] decrypt(DSLContext tx, ULong secretId) {
        var row = tx.select(APP_SECRET.SECRET_GUID, APP_SECRET.SECRET_NAME,
                        APP_SECRET.SECRET_TYPE, APP_SECRET.ENCRYPTED_VALUE, APP_SECRET.NONCE,
                        APP_SECRET.ENCRYPTION_KEY_ID, APP_SECRET.ENCRYPTION_VERSION)
                .from(APP_SECRET).where(APP_SECRET.APP_SECRET_ID.eq(secretId)).fetchOne();
        if (row == null) throw new IllegalStateException("The provider secret does not exist.");
        try {
            return crypto.decrypt(new ApplicationSecretCrypto.EncryptedSecret(
                        row.get(APP_SECRET.ENCRYPTED_VALUE), row.get(APP_SECRET.NONCE),
                        row.get(APP_SECRET.ENCRYPTION_KEY_ID),
                        row.get(APP_SECRET.ENCRYPTION_VERSION).intValue()),
                new ApplicationSecretCrypto.SecretContext(row.get(APP_SECRET.SECRET_GUID),
                        row.get(APP_SECRET.SECRET_NAME), row.get(APP_SECRET.SECRET_TYPE)));
        } catch (RuntimeException failure) {
            observability.decryptionFailed();
            throw failure;
        }
    }

    public void delete(DSLContext tx, ULong secretId) {
        tx.deleteFrom(APP_SECRET).where(APP_SECRET.APP_SECRET_ID.eq(secretId)).execute();
    }

    public static void clear(char[] secret) {
        if (secret != null) Arrays.fill(secret, '\0');
    }
}
