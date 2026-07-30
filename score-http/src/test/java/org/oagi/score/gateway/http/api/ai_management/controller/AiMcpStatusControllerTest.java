package org.oagi.score.gateway.http.api.ai_management.controller;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiMcpStatus;
import org.oagi.score.gateway.http.api.ai_management.model.AiMcpServerStatus;
import org.oagi.score.gateway.http.api.ai_management.service.AiMcpStatusService;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.core.AuthenticatedPrincipal;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMcpStatusControllerTest {

    @Test
    void checksMcpWithTheAuthenticatedRequester() {
        AiMcpStatusService service = mock(AiMcpStatusService.class);
        SessionService sessions = mock(SessionService.class);
        AuthenticatedPrincipal principal = mock(AuthenticatedPrincipal.class);
        ScoreUser requester = mock(ScoreUser.class);
        AiMcpStatus expected = new AiMcpStatus(List.of(new AiMcpServerStatus(
                "connect-center-mcp", AiMcpServerStatus.ConnectionState.CONNECTED, 12)));
        when(sessions.asScoreUser(principal)).thenReturn(requester);
        when(service.check(requester)).thenReturn(expected);

        AiMcpStatus actual = new AiMcpStatusController(service, sessions).status(principal);

        assertThat(actual).isEqualTo(expected);
        verify(service).check(requester);
    }
}
