package org.oagi.score.gateway.http.api.ai_management.catalog.model.profile;

public final class Gpt55ProProfile extends AbstractGpt5Profile {
    public Gpt55ProProfile() {
        super("gpt-5_5-pro", "gpt-5.5-pro", "GPT-5.5 Pro",
                "Quality-first GPT-5.5 model for difficult professional workloads; OpenAI exposes this alias through the Responses API.",
                OpenAiModelProfileSpecs.GPT_55_PRO);
    }
}
