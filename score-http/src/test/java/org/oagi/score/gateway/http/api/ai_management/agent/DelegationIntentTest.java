package org.oagi.score.gateway.http.api.ai_management.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationIntentTest {

    @Test
    void extractsBoundedAgentCountsFromLocalizedAndMixedScriptRequests() {
        assertThat(DelegationIntent.requestedAgentCount(
                "서브에이전트 3개를 동시에 실행해.")).contains(3);
        assertThat(DelegationIntent.requestedAgentCount(
                "세 개의 서브에이전트를 병렬로 실행해.")).contains(3);
        assertThat(DelegationIntent.requestedAgentCount(
                "sub-agent 3개를 동시에 실행해.")).contains(3);
        assertThat(DelegationIntent.requestedAgentCount(
                "3개의 sub-agent를 병렬로 실행해.")).contains(3);
    }

    @Test
    void clampsRequestedAgentCountToThePolicyMaximum() {
        assertThat(DelegationIntent.boundedRequestedAgentCount(
                "Spawn exactly 4 sub-agents in parallel.", 2)).isEqualTo(2);
        assertThat(DelegationIntent.boundedRequestedAgentCount(
                "Use sub-agents.", 4)).isEqualTo(2);
    }

    @Test
    void recognizesLocalizedDelegationAndNegation() {
        assertThat(DelegationIntent.explicitlyRequestsAgents(
                "이 요청에는 sub-agent를 사용해.")).isTrue();
        assertThat(DelegationIntent.explicitlyRequestsAgents(
                "서브에이전트 3개를 동시에 실행해.")).isTrue();
        assertThat(DelegationIntent.explicitlyNegatesAgents(
                "이 요청에는 서브에이전트를 사용하지 마.")).isTrue();
        assertThat(DelegationIntent.explicitlyNegatesAgents(
                "이 요청에는 sub-agent를 사용하지 마.")).isTrue();
    }

    @Test
    void doesNotTreatExplicitEnglishNegationAsFanOut() {
        assertThat(DelegationIntent.explicitlyRequestsFanOut(
                "Do not spawn sub-agents; complete the task directly.")).isFalse();
        assertThat(DelegationIntent.explicitlyRequestsFanOut(
                "Never delegate to agents in parallel.")).isFalse();
        assertThat(DelegationIntent.explicitlyRequestsFanOut(
                "Spawn exactly 2 sub-agents in parallel.")).isTrue();
    }
}
