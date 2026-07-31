package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.controller.payload.AiChangeConfirmationDecisionResponse;
import org.oagi.score.gateway.http.api.ai_management.model.AiChangeDecision;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeApprovalCoordinator;
import org.oagi.score.gateway.http.api.ai_management.service.AiChangeConfirmationService;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.http.HttpStatus;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiChangeConfirmationEndpointTest {

    @Test
    void serializesNullDecisionPayloadThroughBothReservationBoundaries() {
        AiRequestRegistry requests = mock(AiRequestRegistry.class);
        AiChangeConfirmationService confirmations = mock(AiChangeConfirmationService.class);
        AiChangeApprovalCoordinator approvals = mock(AiChangeApprovalCoordinator.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiChangeConfirmationDecisionResponse response = new AiChangeConfirmationDecisionResponse(
                "confirmation-1", "conversation-1", "DENIED", "DENIED", null,
                null, null, null, null, null);
        when(confirmations.sourceRequestId(requester, "conversation-1", "confirmation-1"))
                .thenReturn("request-1");
        when(confirmations.decide(requester, "conversation-1", "confirmation-1", null, null))
                .thenReturn(new AiChangeDecision(response, HttpStatus.ACCEPTED));
        when(confirmations.bindConversation(response, "conversation-1")).thenReturn(response);
        when(requests.whileRequestAndConversationIdle(eq("request-1"), eq("conversation-1"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(2).get());
        when(approvals.whileConfirmationUnreserved(eq("confirmation-1"), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        AiChangeConfirmationEndpoint endpoint = new AiChangeConfirmationEndpoint(
                requests, confirmations, approvals);

        var result = endpoint.decide(requester, "conversation-1", "confirmation-1", null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isSameAs(response);
        verify(confirmations).decide(requester, "conversation-1", "confirmation-1", null, null);
    }
}
