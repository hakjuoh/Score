package org.oagi.score.gateway.http.api.ai_management.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityId;
import org.oagi.score.gateway.http.api.ai_management.model.AiOwnedEntityKind;

import java.util.Optional;

/** Read-side access to the owning user of a record a data-changing tool call would change. */
public interface AiChangeOwnershipQueryRepository {

    /**
     * @param kind kind of record the identifier belongs to
     * @param id identifier taken from the tool arguments
     * @return owning user, or empty when the record is absent or records no owner
     */
    Optional<UserId> findOwner(AiOwnedEntityKind kind, AiOwnedEntityId id);

}
