package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPropertiesTest {

    @Test
    void appliesAnthropicDefaultsThroughSpringAiOptions() {
        StandardEnvironment environment = environment(Map.of(
                "spring.ai.anthropic.chat.max-tokens", "12000",
                "spring.ai.anthropic.chat.temperature", "0.7",
                "spring.ai.anthropic.chat.disable-parallel-tool-use", "true",
                "spring.ai.anthropic.chat.inference-geo", "EU"));
        AnthropicChatProperties properties = Binder.get(environment)
                .bind("spring.ai.anthropic.chat", AnthropicChatProperties.class)
                .orElseThrow(() -> new IllegalStateException("Anthropic properties were not bound"));

        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model("claude-test");
        properties.apply(builder, true);
        AnthropicChatOptions options = builder.build();

        assertThat(options.getMaxTokens()).isEqualTo(12_000);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getDisableParallelToolUse()).isTrue();
        assertThat(options.getInferenceGeo()).isEqualTo("eu");
    }

    @Test
    void appliesOpenAiDefaultsThroughSpringAiOptions() {
        StandardEnvironment environment = environment(Map.of(
                "spring.ai.openai.chat.max-completion-tokens", "8000",
                "spring.ai.openai.chat.temperature", "0.7",
                "spring.ai.openai.chat.parallel-tool-calls", "false",
                "spring.ai.openai.chat.reasoning-effort", "high"));
        OpenAiChatProperties properties = Binder.get(environment)
                .bind("spring.ai.openai.chat", OpenAiChatProperties.class)
                .orElseThrow(() -> new IllegalStateException("OpenAI properties were not bound"));

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("gpt-test");
        properties.apply(builder, true);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getMaxCompletionTokens()).isEqualTo(8_000);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getReasoningEffort()).isEqualTo("high");
    }

    private StandardEnvironment environment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        return environment;
    }
}
