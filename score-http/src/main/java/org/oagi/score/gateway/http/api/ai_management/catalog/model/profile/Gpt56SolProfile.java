package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt56SolProfile extends AbstractGpt5Profile {
    public Gpt56SolProfile() {
        super("gpt-5_6-sol", "gpt-5.6-sol", "GPT-5.6 Sol",
                "Latest frontier agentic model for complex reasoning and tool-driven work.",
                OpenAiModelProfileSpecs.GPT_56);
    }
}
