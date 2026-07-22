package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** Server-owned defaults from {@code spring.ai.openai.chat.*}. */
@ConfigurationProperties("spring.ai.openai.chat")
public class OpenAiChatProperties {

    private Double frequencyPenalty;
    private Map<String, Integer> logitBias = Map.of();
    private Boolean logprobs;
    private Integer topLogprobs;
    private Integer maxTokens;
    private Integer maxCompletionTokens;
    private Integer n;
    private Double presencePenalty;
    private Integer seed;
    private List<String> stop = List.of();
    private Double temperature;
    private Double topP;
    private Boolean parallelToolCalls;
    private Boolean store;
    private Map<String, String> metadata = Map.of();
    private String reasoningEffort;
    private String verbosity;
    private String serviceTier;
    private String promptCacheKey;
    private Map<String, Object> extraBody = Map.of();

    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }
    public Map<String, Integer> getLogitBias() { return logitBias; }
    public void setLogitBias(Map<String, Integer> logitBias) {
        this.logitBias = logitBias != null ? Map.copyOf(logitBias) : Map.of();
    }
    public Boolean getLogprobs() { return logprobs; }
    public void setLogprobs(Boolean logprobs) { this.logprobs = logprobs; }
    public Integer getTopLogprobs() { return topLogprobs; }
    public void setTopLogprobs(Integer topLogprobs) { this.topLogprobs = topLogprobs; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Integer getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(Integer maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }
    public Integer getN() { return n; }
    public void setN(Integer n) { this.n = n; }
    public Double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; }
    public Integer getSeed() { return seed; }
    public void setSeed(Integer seed) { this.seed = seed; }
    public List<String> getStop() { return stop; }
    public void setStop(List<String> stop) { this.stop = stop != null ? List.copyOf(stop) : List.of(); }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Boolean getParallelToolCalls() { return parallelToolCalls; }
    public void setParallelToolCalls(Boolean parallelToolCalls) { this.parallelToolCalls = parallelToolCalls; }
    public Boolean getStore() { return store; }
    public void setStore(Boolean store) { this.store = store; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public String getVerbosity() { return verbosity; }
    public void setVerbosity(String verbosity) { this.verbosity = verbosity; }
    public String getServiceTier() { return serviceTier; }
    public void setServiceTier(String serviceTier) { this.serviceTier = serviceTier; }
    public String getPromptCacheKey() { return promptCacheKey; }
    public void setPromptCacheKey(String promptCacheKey) { this.promptCacheKey = promptCacheKey; }
    public Map<String, Object> getExtraBody() { return extraBody; }
    public void setExtraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody != null ? Map.copyOf(extraBody) : Map.of();
    }

    public void apply(OpenAiChatOptions.Builder builder, boolean reasoningModel) {
        if (frequencyPenalty != null && !reasoningModel) builder.frequencyPenalty(frequencyPenalty);
        if (!logitBias.isEmpty() && !reasoningModel) builder.logitBias(logitBias);
        if (logprobs != null && !reasoningModel) builder.logprobs(logprobs);
        if (topLogprobs != null && !reasoningModel) builder.topLogprobs(topLogprobs);
        if (reasoningModel) {
            if (maxCompletionTokens != null) builder.maxCompletionTokens(maxCompletionTokens);
        } else if (maxTokens != null) {
            builder.maxTokens(maxTokens);
        }
        if (n != null) builder.n(n);
        if (presencePenalty != null && !reasoningModel) builder.presencePenalty(presencePenalty);
        if (seed != null && !reasoningModel) builder.seed(seed);
        if (!stop.isEmpty() && !reasoningModel) builder.stop(stop);
        if (temperature != null && !reasoningModel) builder.temperature(temperature);
        if (topP != null && !reasoningModel) builder.topP(topP);
        if (parallelToolCalls != null) builder.parallelToolCalls(parallelToolCalls);
        if (store != null) builder.store(store);
        if (!metadata.isEmpty()) builder.metadata(metadata);
        if (StringUtils.hasText(reasoningEffort) && reasoningModel) builder.reasoningEffort(reasoningEffort.strip());
        if (StringUtils.hasText(verbosity) && reasoningModel) builder.verbosity(verbosity.strip());
        if (StringUtils.hasText(serviceTier)) builder.serviceTier(serviceTier.strip());
        if (StringUtils.hasText(promptCacheKey)) builder.promptCacheKey(promptCacheKey.strip());
        if (!extraBody.isEmpty()) builder.extraBody(extraBody);
    }
}
