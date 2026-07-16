package org.oagi.score.gateway.http.configuration.initializer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisFlushAllInitializer implements InitializingBean {

    private final Log logger = LogFactory.getLog(getClass());

    private final RedisTemplate redisTemplate;
    private final Environment environment;

    public RedisFlushAllInitializer(RedisTemplate redisTemplate, Environment environment) {
        this.redisTemplate = redisTemplate;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!environment.getProperty("score.redis.flush-on-startup", Boolean.class, false)) {
            return;
        }
        logger.warn("Removing all keys from Redis because score.redis.flush-on-startup is explicitly enabled.");
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

}
