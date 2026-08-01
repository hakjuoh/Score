package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt56LunaProfile extends AbstractGpt5Profile {
    public Gpt56LunaProfile() {
        super("gpt-5_6-luna", "gpt-5.6-luna", "GPT-5.6 Luna",
                "Fast and affordable agentic model for lightweight everyday tasks.",
                OpenAiModelProfileSpecs.GPT_56);
    }
}
