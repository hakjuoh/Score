package org.oagi.score.gateway.http.api.ai_management.policy.repository;

import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.ai_management.policy.model.AiUserPolicy;

import java.util.Optional;

public interface AiPolicyQueryRepository {
    Optional<AiUserPolicy> find(UserId userId);
}
