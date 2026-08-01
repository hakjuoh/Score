package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.openai;

public final class Gpt54ProProfile extends AbstractGpt5Profile {
    public Gpt54ProProfile() {
        super("gpt-5_4-pro", "gpt-5.4-pro", "GPT-5.4 Pro",
                "Quality-first GPT-5.4 model for complex professional workloads; OpenAI exposes this alias through the Responses API only.",
                OpenAiModelProfileSpecs.GPT_54_PRO);
    }
}
