package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentInputGuardrail;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiGuardrailConfigurationTest {

    @Test
    void baselineRedactsSecretsBeforeAnyAgentSeesTheTurn() {
        var chain = new AiGuardrailConfiguration().agentInputGuardrailChain();
        var scope = new ExecutionScope("request", "conversation", "user", 1,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());

        var outcome = chain.evaluate(new AgentInputGuardrail.Request(
                AgentInputGuardrail.Scope.TURN_LOCAL,
                new AiMessage.User("api_key=do-not-disclose"), List.of(), scope, Map.of()));

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.input().content()).isEqualTo("api_key=[REDACTED]");
        assertThat(outcome.decisions()).singleElement()
                .satisfies(decision -> assertThat(decision.action().name()).isEqualTo("REWRITE"));
    }
}
