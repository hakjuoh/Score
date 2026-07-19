package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.oagi.score.gateway.http.api.ai_management.runtime.AiRuntime;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads self-contained Markdown worker definitions from the classpath. */
@Component
public final class AiAgentCatalog {

    private static final String AGENTS = "classpath*:prompts/agent-*.md";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final Map<String, AiAgentDefinition> agents;

    public AiAgentCatalog(ResourceLoader resourceLoader) {
        ResourcePatternResolver resources = new PathMatchingResourcePatternResolver(resourceLoader);
        try {
            Map<String, AiAgentDefinition> loaded = new LinkedHashMap<>();
            for (Resource resource : resources.getResources(AGENTS)) {
                AgentFile agentFile = read(resource);
                Descriptor descriptor = agentFile.descriptor();
                validate(descriptor);
                String id = descriptor.id().strip().toLowerCase(Locale.ROOT);
                AiAgentDefinition previous = loaded.put(id, new AiAgentDefinition(
                        id, descriptor.name().strip(), descriptor.description().strip(), agentFile.prompt(),
                        AiRuntime.ToolPolicy.valueOf(descriptor.toolPolicy().strip().toUpperCase(Locale.ROOT))));
                if (previous != null) throw new IllegalStateException("Duplicate AI agent id: " + id);
            }
            if (loaded.isEmpty()) throw new IllegalStateException("The AI agent registry is empty.");
            agents = Map.copyOf(loaded);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not load the AI agent registry.", failure);
        }
    }

    public List<AiAgentDefinition> all() {
        return agents.values().stream().sorted(java.util.Comparator.comparing(AiAgentDefinition::id)).toList();
    }

    public AiAgentDefinition require(String id) {
        String normalized = StringUtils.hasText(id) ? id.strip().toLowerCase(Locale.ROOT) : "";
        AiAgentDefinition agent = agents.get(normalized);
        if (agent == null) throw new IllegalArgumentException("Unknown AI agent: " + id);
        return agent;
    }

    public AiAgentDefinition defaultAgent() {
        AiAgentDefinition agent = agents.get("general-purpose");
        return agent != null ? agent : all().getFirst();
    }

    public String plannerRegistry() {
        return all().stream().map(agent -> "- %s: %s".formatted(agent.id(), agent.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private AgentFile read(Resource resource) throws IOException {
        String markdown;
        try (var input = resource.getInputStream()) {
            markdown = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
        }
        if (markdown.startsWith("\uFEFF")) markdown = markdown.substring(1);
        if (!markdown.startsWith("---\n")) {
            throw invalid(resource, "missing opening YAML frontmatter delimiter");
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) throw invalid(resource, "missing closing YAML frontmatter delimiter");
        Descriptor descriptor;
        try {
            descriptor = YAML.readValue(markdown.substring(4, end), Descriptor.class);
        } catch (IOException failure) {
            throw invalid(resource, "invalid YAML frontmatter", failure);
        }
        String prompt = markdown.substring(end + 5).strip();
        if (!StringUtils.hasText(prompt)) throw invalid(resource, "empty prompt body");
        return new AgentFile(descriptor, prompt);
    }

    private void validate(Descriptor descriptor) {
        if (descriptor == null || !StringUtils.hasText(descriptor.id())
                || !descriptor.id().matches("[a-z0-9][a-z0-9-]{1,63}")
                || !StringUtils.hasText(descriptor.name())
                || !StringUtils.hasText(descriptor.description())
                || !StringUtils.hasText(descriptor.toolPolicy())) {
            throw new IllegalStateException("An AI agent Markdown file contains invalid frontmatter.");
        }
    }

    private IllegalStateException invalid(Resource resource, String reason) {
        return invalid(resource, reason, null);
    }

    private IllegalStateException invalid(Resource resource, String reason, Exception cause) {
        String description = resource.getDescription();
        String message = "Invalid AI agent definition " + description + ": " + reason;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private record Descriptor(String id, String name, String description, String toolPolicy) {}

    private record AgentFile(Descriptor descriptor, String prompt) {}
}
