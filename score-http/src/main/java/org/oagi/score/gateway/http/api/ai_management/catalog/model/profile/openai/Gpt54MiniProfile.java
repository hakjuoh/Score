package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt54MiniProfile extends AbstractGpt5Profile {
    public Gpt54MiniProfile() {
        super("gpt-5_4-mini", "gpt-5.4-mini", "GPT-5.4 mini",
                "Efficient GPT-5.4 model balancing capability, latency, and cost.",
                OpenAiModelProfileSpecs.GPT_54_MINI);
    }
}
