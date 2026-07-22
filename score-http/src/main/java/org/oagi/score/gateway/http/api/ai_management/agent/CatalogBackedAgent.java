package org.oagi.score.gateway.http.api.ai_management.agent;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Resolves a fixed Agent definition from the common catalog using its Spring registration name. */
public abstract class CatalogBackedAgent implements Agent {

    private final AiAgentCatalog catalog;

    protected CatalogBackedAgent(AiAgentCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public final AgentDefinition definition() {
        Component component = AnnotatedElementUtils.findMergedAnnotation(
                ClassUtils.getUserClass(this), Component.class);
        String registration = component != null ? component.value() : null;
        if (!StringUtils.hasText(registration)) {
            throw new IllegalStateException(
                    "A catalog-backed Agent requires an explicit @Component registration name.");
        }
        return catalog.systemDefinition(registration);
    }
}
