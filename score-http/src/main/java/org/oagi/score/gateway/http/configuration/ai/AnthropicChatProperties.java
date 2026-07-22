package org.oagi.score.gateway.http.configuration.ai;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicServiceTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.List;

/** Server-owned defaults from {@code spring.ai.anthropic.chat.*}. */
@ConfigurationProperties("spring.ai.anthropic.chat")
public class AnthropicChatProperties {

    private Integer maxTokens;
    private List<String> stopSequences = List.of();
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Boolean disableParallelToolUse;
    private String inferenceGeo;
    private AnthropicServiceTier serviceTier;

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public List<String> getStopSequences() { return stopSequences; }
    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences != null ? List.copyOf(stopSequences) : List.of();
    }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Boolean getDisableParallelToolUse() { return disableParallelToolUse; }
    public void setDisableParallelToolUse(Boolean disableParallelToolUse) {
        this.disableParallelToolUse = disableParallelToolUse;
    }
    public String getInferenceGeo() { return inferenceGeo; }
    public void setInferenceGeo(String inferenceGeo) { this.inferenceGeo = inferenceGeo; }
    public AnthropicServiceTier getServiceTier() { return serviceTier; }
    public void setServiceTier(AnthropicServiceTier serviceTier) { this.serviceTier = serviceTier; }

    public void apply(AnthropicChatOptions.Builder builder, boolean thinkingModel) {
        if (maxTokens != null) builder.maxTokens(maxTokens);
        if (!stopSequences.isEmpty()) builder.stopSequences(stopSequences);
        if (temperature != null && !thinkingModel) builder.temperature(temperature);
        if (topP != null && !thinkingModel) builder.topP(topP);
        if (topK != null && !thinkingModel) builder.topK(topK);
        if (disableParallelToolUse != null) builder.disableParallelToolUse(disableParallelToolUse);
        if (StringUtils.hasText(inferenceGeo)) builder.inferenceGeo(inferenceGeo.strip().toLowerCase());
        if (serviceTier != null) builder.serviceTier(serviceTier);
    }
}
