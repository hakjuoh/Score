package org.oagi.score.gateway.http.api.release_management.service;

import org.oagi.score.gateway.http.common.model.event.EventListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

/** Registers the fully initialized AOP proxy as the Redis Release event listener. */
@Component
final class ReleaseEventListenerRegistration implements SmartInitializingSingleton {

    private final EventListenerContainer listeners;
    private final ObjectProvider<ReleaseCommandService> serviceProvider;

    ReleaseEventListenerRegistration(
            EventListenerContainer listeners,
            ObjectProvider<ReleaseCommandService> serviceProvider) {
        this.listeners = listeners;
        this.serviceProvider = serviceProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ReleaseCommandService proxiedService = serviceProvider.getObject();
        listeners.addMessageListener(
                proxiedService,
                "onReleaseCreateRequestEventReceived",
                new ChannelTopic(ReleaseCommandService.RELEASE_CREATE_REQUEST_EVENT));
        listeners.addMessageListener(
                proxiedService,
                "onReleaseCleanupEventReceived",
                new ChannelTopic(ReleaseCommandService.RELEASE_CLEANUP_EVENT));
    }
}
