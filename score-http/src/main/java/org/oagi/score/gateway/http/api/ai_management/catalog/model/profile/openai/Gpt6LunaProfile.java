package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt6LunaProfile extends AbstractGpt6Profile {
    public Gpt6LunaProfile() {
        super("gpt-6-luna", "gpt-6-luna", "GPT-6 Luna",
                "Most efficient model for focused, high-volume tasks.",
                OpenAiModelProfileSpecs.GPT_6_LUNA);
    }
}
