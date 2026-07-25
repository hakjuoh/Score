package org.oagi.score.gateway.http.api.ai_management.agent;

/** Releases and reacquires runner capacity while a run waits for user approval. */
public interface AgentApprovalWaitLifecycle {

    AgentApprovalWaitLifecycle NOOP = new AgentApprovalWaitLifecycle() {
        @Override
        public void suspendForApproval() {
        }

        @Override
        public void resumeAfterApproval() {
        }
    };

    void suspendForApproval();

    void resumeAfterApproval();
}
