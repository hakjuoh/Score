package org.oagi.score.gateway.http.api.release_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.common.model.event.EventListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.listener.ChannelTopic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseEventListenerRegistrationTest {

    @Test
    void registersThePostProcessedServiceObtainedAfterSingletonInitialization() {
        EventListenerContainer listeners = mock(EventListenerContainer.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ReleaseCommandService> provider = mock(ObjectProvider.class);
        ReleaseCommandService proxiedService = mock(ReleaseCommandService.class);
        when(provider.getObject()).thenReturn(proxiedService);

        new ReleaseEventListenerRegistration(listeners, provider).afterSingletonsInstantiated();

        verify(listeners).addMessageListener(
                proxiedService,
                "onReleaseCreateRequestEventReceived",
                new ChannelTopic(ReleaseCommandService.RELEASE_CREATE_REQUEST_EVENT));
        verify(listeners).addMessageListener(
                proxiedService,
                "onReleaseCleanupEventReceived",
                new ChannelTopic(ReleaseCommandService.RELEASE_CLEANUP_EVENT));
    }
}
