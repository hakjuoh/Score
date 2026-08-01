package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt56TerraProfile extends AbstractGpt5Profile {
    public Gpt56TerraProfile() {
        super("gpt-5_6-terra", "gpt-5.6-terra", "GPT-5.6 Terra",
                "Balanced agentic model for everyday reasoning and tool-driven work.",
                OpenAiModelProfileSpecs.GPT_56);
    }
}
