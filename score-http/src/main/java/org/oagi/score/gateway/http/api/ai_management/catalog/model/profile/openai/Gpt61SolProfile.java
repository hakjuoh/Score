package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt61SolProfile extends AbstractGpt6Profile {
    public Gpt61SolProfile() {
        super("gpt-6_1-sol", "gpt-6.1-sol", "GPT-6.1 Sol",
                "Near-Astra performance for complex coding, computer use, and professional work at a lower cost.",
                OpenAiModelProfileSpecs.GPT_61_SOL);
    }
}
