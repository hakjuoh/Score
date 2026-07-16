package org.oagi.score.gateway.http.configuration.initializer;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisFlushAllInitializerTest {

    @Test
    void neverFlushesSharedRedisStateByDefault() throws Exception {
        RedisTemplate template = mock(RedisTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty("score.redis.flush-on-startup", Boolean.class, false))
                .thenReturn(false);

        new RedisFlushAllInitializer(template, environment).afterPropertiesSet();

        verify(template, never()).getConnectionFactory();
    }

    @Test
    void flushesOnlyWhenTheDestructiveMaintenanceSwitchIsExplicitlyEnabled() throws Exception {
        RedisTemplate template = mock(RedisTemplate.class);
        Environment environment = mock(Environment.class);
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisServerCommands commands = mock(RedisServerCommands.class);
        when(environment.getProperty("score.redis.flush-on-startup", Boolean.class, false))
                .thenReturn(true);
        when(template.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(commands);

        new RedisFlushAllInitializer(template, environment).afterPropertiesSet();

        verify(commands).flushAll();
        verify(connection).close();
    }
}
