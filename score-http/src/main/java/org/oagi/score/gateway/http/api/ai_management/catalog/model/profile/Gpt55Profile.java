package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt55Profile extends AbstractGpt5Profile {
    public Gpt55Profile() {
        super("gpt-5_5", "gpt-5.5", "GPT-5.5",
                "General-purpose GPT-5.5 reasoning and agentic model.",
                OpenAiModelProfileSpecs.GPT_55);
    }
}
