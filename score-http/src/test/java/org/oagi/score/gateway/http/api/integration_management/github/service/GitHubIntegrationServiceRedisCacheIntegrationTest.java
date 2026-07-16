package org.oagi.score.gateway.http.api.integration_management.github.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.integration_management.github.client.GitHubApiClient;
import org.oagi.score.gateway.http.api.integration_management.github.config.GitHubIntegrationProperties;
import org.oagi.score.gateway.http.api.integration_management.github.model.ProjectFieldOptions;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class GitHubIntegrationServiceRedisCacheIntegrationTest {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sharesResolvedProjectReferencesAcrossServiceInstances() {
        String owner = "cache-test-" + UUID.randomUUID();
        GitHubIntegrationProperties properties = mock(GitHubIntegrationProperties.class);
        when(properties.isProjectConfigured()).thenReturn(true);
        when(properties.getProjectOwnerType()).thenReturn("org");
        when(properties.getProjectOwner()).thenReturn(owner);
        when(properties.getProjectNumber()).thenReturn(7);
        when(properties.getProjectStatusFieldName()).thenReturn("Status");
        ProjectFieldOptions fieldOptions = new ProjectFieldOptions(
                Map.of("WIP", "Implementing"), "New");
        GitHubApiClient api = mock(GitHubApiClient.class);
        when(api.fetchProject("token", "org", owner, 7)).thenReturn(
                new GitHubApiClient.ProjectData("project-node", "Shared board", List.of(
                        new GitHubApiClient.SingleSelectField("status-field", "Status", List.of(
                                new GitHubApiClient.SingleSelectOption("new-option", "New", "GRAY"),
                                new GitHubApiClient.SingleSelectOption(
                                        "implementing-option", "Implementing", "BLUE"))))));

        GitHubIntegrationService first = new GitHubIntegrationService(
                properties, fieldOptions, api, redisTemplate, redissonClient, objectMapper);
        GitHubIntegrationService second = new GitHubIntegrationService(
                properties, fieldOptions, api, redisTemplate, redissonClient, objectMapper);
        ScoreUser user = new ScoreUser(new UserId(BigInteger.valueOf(991_337L)),
                "cache-test", "Cache Test", null, false, List.of());
        String tokenKey = "score:integration:github:token:" + user.userId();
        String cacheKey = first.projectRefsCacheKey();
        redisTemplate.opsForValue().set(tokenKey, "token");

        try {
            GitHubIntegrationService.ProjectField firstResult = first.getProjectField(user);
            GitHubIntegrationService.ProjectField secondResult = second.getProjectField(user);

            assertThat(firstResult).isEqualTo(secondResult);
            assertThat(secondResult.projectTitle()).isEqualTo("Shared board");
            assertThat(secondResult.options()).extracting(GitHubIntegrationService.FieldOption::name)
                    .containsExactly("New", "Implementing");
            assertThat(redissonClient.getBucket(cacheKey, StringCodec.INSTANCE).get()).isNotNull();
            verify(api, times(1)).fetchProject("token", "org", owner, 7);
        } finally {
            redisTemplate.delete(tokenKey);
            redissonClient.getBucket(cacheKey, StringCodec.INSTANCE).delete();
        }
    }
}
