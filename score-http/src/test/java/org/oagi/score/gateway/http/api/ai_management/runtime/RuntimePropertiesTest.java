package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePropertiesTest {

    @Test
    void bindsAndAppliesSpringAiAnthropicChatDefaultsWithModelCapabilityFiltering() {
        StandardEnvironment environment = environment(Map.of(
                "spring.ai.anthropic.chat.max-tokens", "12000",
                "spring.ai.anthropic.chat.temperature", "0.7",
                "spring.ai.anthropic.chat.top-p", "0.9",
                "spring.ai.anthropic.chat.top-k", "20",
                "spring.ai.anthropic.chat.disable-parallel-tool-use", "true",
                "spring.ai.anthropic.chat.inference-geo", "EU"));
        AnthropicRuntimeProperties properties = Binder.get(environment)
                .bind("spring.ai.anthropic.chat", AnthropicRuntimeProperties.class)
                .orElseThrow(() -> new IllegalStateException("Anthropic runtime properties were not bound"));

        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model("claude-test");
        properties.apply(builder, true);
        AnthropicChatOptions options = builder.build();

        assertThat(options.getMaxTokens()).isEqualTo(12_000);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getTopP()).isNull();
        assertThat(options.getTopK()).isNull();
        assertThat(options.getDisableParallelToolUse()).isTrue();
        assertThat(options.getInferenceGeo()).isEqualTo("eu");
    }

    @Test
    void bindsAndAppliesSpringAiOpenAiChatDefaultsWithoutSendingUnsupportedReasoningOptions() {
        StandardEnvironment environment = environment(Map.of(
                "spring.ai.openai.chat.max-tokens", "2000",
                "spring.ai.openai.chat.max-completion-tokens", "8000",
                "spring.ai.openai.chat.temperature", "0.7",
                "spring.ai.openai.chat.frequency-penalty", "0.5",
                "spring.ai.openai.chat.parallel-tool-calls", "false",
                "spring.ai.openai.chat.reasoning-effort", "high",
                "spring.ai.openai.chat.verbosity", "low"));
        OpenAiRuntimeProperties properties = Binder.get(environment)
                .bind("spring.ai.openai.chat", OpenAiRuntimeProperties.class)
                .orElseThrow(() -> new IllegalStateException("OpenAI runtime properties were not bound"));

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("gpt-5-test");
        properties.apply(builder, true);
        OpenAiChatOptions options = builder.build();

        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getMaxCompletionTokens()).isEqualTo(8_000);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getFrequencyPenalty()).isNull();
        assertThat(options.getParallelToolCalls()).isFalse();
        assertThat(options.getReasoningEffort()).isEqualTo("high");
        assertThat(options.getVerbosity()).isEqualTo("low");
    }

    private StandardEnvironment environment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        return environment;
    }
}
