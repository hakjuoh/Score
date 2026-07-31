package org.oagi.score.gateway.http.api.ai_management.trajectory;

import org.springframework.util.StringUtils;

import java.util.ArrayDeque;

/** Owns the disclosure/accounting fences and thread-scoped agent-run correlation. */
final class AiTrajectoryRuntimeState {

    private volatile boolean disclosureSealed;
    private volatile boolean usageAccountingSealed;
    private final ThreadLocal<ArrayDeque<String>> activeAgentRuns =
            ThreadLocal.withInitial(ArrayDeque::new);

    boolean disclosureSealed() {
        return disclosureSealed;
    }

    void sealDisclosure() {
        disclosureSealed = true;
    }

    boolean usageAccountingSealed() {
        return usageAccountingSealed;
    }

    void sealUsageAccounting() {
        usageAccountingSealed = true;
    }

    String activeAgentRunId() {
        return activeAgentRuns.get().peek();
    }

    Runnable activateAgentRun(String runId) {
        if (!StringUtils.hasText(runId)) return () -> { };
        activeAgentRuns.get().push(runId.strip());
        return () -> {
            ArrayDeque<String> active = activeAgentRuns.get();
            if (!active.isEmpty()) active.pop();
            if (active.isEmpty()) activeAgentRuns.remove();
        };
    }
}
