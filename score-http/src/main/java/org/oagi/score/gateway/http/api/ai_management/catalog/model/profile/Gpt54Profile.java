package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt54Profile extends AbstractGpt5Profile {
    public Gpt54Profile() {
        super("gpt-5_4", "gpt-5.4", "GPT-5.4",
                "General-purpose GPT-5.4 reasoning and agentic model.",
                OpenAiModelProfileSpecs.GPT_54);
    }
}
