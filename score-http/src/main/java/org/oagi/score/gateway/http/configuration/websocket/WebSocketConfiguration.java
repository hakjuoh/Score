package org.oagi.score.gateway.http.configuration.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.session.Session;
import org.springframework.session.web.socket.config.annotation.AbstractSessionWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration
        extends AbstractSessionWebSocketMessageBrokerConfigurer<Session> {

    private static final int AI_ATTACHMENT_MESSAGE_SIZE_LIMIT = 32 * 1024 * 1024;

    private final AuthenticatedUserChannelInterceptor authenticatedUserChannelInterceptor;

    public WebSocketConfiguration(AuthenticatedUserChannelInterceptor authenticatedUserChannelInterceptor) {
        this.authenticatedUserChannelInterceptor = authenticatedUserChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticatedUserChannelInterceptor);
    }

    @Override
    public void configureStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new HttpSessionHandshakeInterceptor());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(AI_ATTACHMENT_MESSAGE_SIZE_LIMIT);
        registration.setSendBufferSizeLimit(AI_ATTACHMENT_MESSAGE_SIZE_LIMIT);
    }
}
