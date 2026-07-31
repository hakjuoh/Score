package org.oagi.score.gateway.http.api.ai_management.policy.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;

public interface AiPolicyCommandRepository {
    AiUserPolicy save(AiUserPolicy policy, UserId actorUserId, Long expectedVersion);

    void delete(UserId targetUserId, UserId actorUserId, long expectedVersion);
}
