package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq.JooqAiQuotaRepository;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;

/** Repairs reservations left behind by process termination or provider-call abandonment. */
@Service
public class AiQuotaReconciliationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiQuotaReconciliationService.class);

    private final JooqAiQuotaRepository quotas;
    private final ScoreAiProperties properties;
    private final AiRequestRegistry requests;
    private final ScoreAiObservability observability;

    @org.springframework.beans.factory.annotation.Autowired
    public AiQuotaReconciliationService(JooqAiQuotaRepository quotas,
                                        ScoreAiProperties properties,
                                        AiRequestRegistry requests,
                                        ScoreAiObservability observability) {
        this.quotas = quotas;
        this.properties = properties;
        this.requests = requests;
        this.observability = observability;
    }

    AiQuotaReconciliationService(JooqAiQuotaRepository quotas,
                                 ScoreAiProperties properties,
                                 AiRequestRegistry requests) {
        this(quotas, properties, requests, ScoreAiObservability.noop());
    }

    @Scheduled(fixedDelayString = "${score.ai.quota.reconciliation-interval:1m}")
    public void reconcile() {
        var result = quotas.reconcileStaleDetailed(
                properties.getQuota().getReservationTimeout(),
                properties.getQuota().getReconciliationBatchSize(),
                requests::isLogicallyActive);
        if (result.count() > 0) {
            observability.quotaReconciled(result.count());
            observability.quotaConsumed(result.consumedTokens(), result.consumedTokens());
            LOGGER.warn("Conservatively settled {} abandoned AI token reservations ({} tokens).",
                    result.count(), result.consumedTokens());
        }
    }
}
