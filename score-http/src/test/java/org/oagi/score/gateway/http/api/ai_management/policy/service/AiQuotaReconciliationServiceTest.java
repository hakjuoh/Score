package org.oagi.score.gateway.http.api.ai_management.policy.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.policy.repository.jooq.JooqAiQuotaRepository;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.time.Duration;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuotaReconciliationServiceTest {

    @Test
    void passesSharedRequestLivenessGuardToStaleReservationRepair() {
        JooqAiQuotaRepository quotas = mock(JooqAiQuotaRepository.class);
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getQuota().setReservationTimeout(Duration.ofMinutes(7));
        properties.getQuota().setReconciliationBatchSize(23);
        when(requests.isLogicallyActive("active-request")).thenReturn(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Predicate<String>> guard =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(quotas.reconcileStaleDetailed(eq(Duration.ofMinutes(7)), eq(23),
                org.mockito.ArgumentMatchers.<Predicate<String>>any()))
                .thenAnswer(invocation -> {
                    guard.set(invocation.getArgument(2));
                    return new JooqAiQuotaRepository.ReconciliationResult(0, 0L);
                });

        new AiQuotaReconciliationService(quotas, properties, requests).reconcile();

        assertThat(guard.get()).isNotNull();
        assertThat(guard.get().test("active-request")).isTrue();
        verify(requests).isLogicallyActive("active-request");
    }
}
