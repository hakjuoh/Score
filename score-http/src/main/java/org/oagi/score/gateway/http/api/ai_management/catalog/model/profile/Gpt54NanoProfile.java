package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt54NanoProfile extends AbstractGpt5Profile {
    public Gpt54NanoProfile() {
        super("gpt-5_4-nano", "gpt-5.4-nano", "GPT-5.4 nano",
                "Fast, economical GPT-5.4 model for lightweight tasks.",
                OpenAiModelProfileSpecs.GPT_54_NANO);
    }
}
