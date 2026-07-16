package org.oagi.score.gateway.http.configuration.websocket;

import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.security.SessionService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.Map;

@Component
public class WebSocketSessionUserResolver {

    public static final String connectCenter_USER_HEADER = "scoreUser";

    private final SessionService sessionService;

    public WebSocketSessionUserResolver(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public ScoreUser resolve(Principal principal, Map<String, Object> sessionAttributes) {
        ScoreUser requester = requesterFromPrincipal(principal);
        if (requester != null) {
            return requester;
        }

        Authentication authentication = authenticationFromSession(sessionAttributes);
        if (authentication != null &&
                authentication.getPrincipal() instanceof AuthenticatedPrincipal authenticatedPrincipal) {
            return sessionService.asScoreUser(authenticatedPrincipal);
        }
        if (authentication != null && StringUtils.hasLength(authentication.getName())) {
            return sessionService.getScoreUserByUsername(authentication.getName());
        }

        String principalName = principalNameFromSession(sessionAttributes);
        if (StringUtils.hasLength(principalName)) {
            return sessionService.getScoreUserByUsername(principalName);
        }

        throw new AuthenticationCredentialsNotFoundException(
                "Score user cannot be resolved from WebSocket session.");
    }

    private ScoreUser requesterFromPrincipal(Principal principal) {
        if (principal instanceof Authentication authentication &&
                authentication.getPrincipal() instanceof AuthenticatedPrincipal authenticatedPrincipal) {
            return sessionService.asScoreUser(authenticatedPrincipal);
        }
        if (principal instanceof AuthenticatedPrincipal authenticatedPrincipal) {
            return sessionService.asScoreUser(authenticatedPrincipal);
        }
        if (principal != null && StringUtils.hasLength(principal.getName())) {
            return sessionService.getScoreUserByUsername(principal.getName());
        }
        return null;
    }

    private Authentication authenticationFromSession(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return null;
        }
        Object securityContext = sessionAttributes.get(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (securityContext instanceof SecurityContext context) {
            return context.getAuthentication();
        }
        return null;
    }

    private String principalNameFromSession(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            return null;
        }
        Object principalName = sessionAttributes.get(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
        return principalName != null ? principalName.toString() : null;
    }
}
