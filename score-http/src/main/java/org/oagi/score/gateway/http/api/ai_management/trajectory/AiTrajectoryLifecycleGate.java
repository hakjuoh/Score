package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.oagi.score.gateway.http.api.ai_management.model.AiUsageSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Serial lifecycle boundary that closes disclosure before accepting late callbacks. */
final class AiTrajectoryLifecycleGate {

    private final AiTrajectoryRuntimeState runtimeState;
    private final AiTrajectoryInteractions interactions;
    private final AiTrajectoryToolCalls toolCalls;

    AiTrajectoryLifecycleGate(AiTrajectoryRuntimeState runtimeState,
                              AiTrajectoryInteractions interactions,
                              AiTrajectoryToolCalls toolCalls) {
        this.runtimeState = runtimeState;
        this.interactions = interactions;
        this.toolCalls = toolCalls;
    }

    void fanOutUsage(String fanoutId, String executionKind, List<AiUsageSnapshot> agents) {
        if (!runtimeState.disclosureSealed()) {
            interactions.settledFanOutUsage(fanoutId, executionKind, agents);
        }
    }

    void settledFanOutUsage(String fanoutId, String executionKind,
                            List<AiUsageSnapshot> agents) {
        interactions.settledFanOutUsage(fanoutId, executionKind, agents);
    }

    void lifecycle(String subtype, String content, Map<String, Object> metadata) {
        if (!runtimeState.disclosureSealed()) {
            interactions.lifecycle(subtype, content, metadata);
        }
    }

    void verifyActive() {
        if (runtimeState.disclosureSealed()) {
            throw new CancellationException("The Agent execution has already terminated.");
        }
    }

    <T> T callWhileActive(Supplier<T> action) {
        verifyActive();
        return Objects.requireNonNull(action, "action").get();
    }

    void terminalLifecycle(String subtype, String content, Map<String, Object> metadata) {
        if (runtimeState.disclosureSealed()) return;
        try {
            interactions.lifecycle(subtype, content, metadata);
        } finally {
            sealDisclosure();
        }
    }

    void sealDisclosure() {
        runtimeState.sealDisclosure();
        toolCalls.clearPending();
    }

    void sealUsageAccounting() {
        runtimeState.sealUsageAccounting();
    }
}
