package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt6AstraProfile extends AbstractGpt6Profile {
    public Gpt6AstraProfile() {
        super("gpt-6-astra", "gpt-6-astra", "GPT-6 Astra",
                "Most capable model for the most demanding reasoning, coding, and professional work.",
                OpenAiModelProfileSpecs.GPT_6_ASTRA);
    }
}
