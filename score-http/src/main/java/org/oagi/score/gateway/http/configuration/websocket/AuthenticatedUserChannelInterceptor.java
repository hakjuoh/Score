package org.oagi.score.gateway.http.configuration.websocket;

import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthenticatedUserChannelInterceptor implements ChannelInterceptor {

    private final WebSocketSessionUserResolver userResolver;

    public AuthenticatedUserChannelInterceptor(WebSocketSessionUserResolver userResolver) {
        this.userResolver = userResolver;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!requiresAuthenticatedUser(accessor)) {
            return message;
        }

        ScoreUser requester;
        try {
            requester = userResolver.resolve(accessor.getUser(), accessor.getSessionAttributes());
        } catch (AuthenticationException e) {
            throw new AuthenticationCredentialsNotFoundException(
                    "AUTHENTICATION_FAILED: Your session is no longer valid. Please sign in again.", e);
        }
        accessor.setHeader(WebSocketSessionUserResolver.connectCenter_USER_HEADER, requester);
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private boolean requiresAuthenticatedUser(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return false;
        }
        String destination = accessor.getDestination();
        if (!StringUtils.hasLength(destination)) {
            return false;
        }
        return (accessor.getCommand() == StompCommand.SEND && destination.startsWith("/app/")) ||
                (accessor.getCommand() == StompCommand.SUBSCRIBE
                        && (destination.startsWith("/topic/") || destination.startsWith("/user/queue/")));
    }
}
