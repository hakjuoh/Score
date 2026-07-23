package org.oagi.score.gateway.http.api.ai_management.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDefinition;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentDescriptor;
import org.oagi.score.gateway.http.api.ai_management.model.AiAgentFile;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Objects;

/** Loads Agent definitions and reusable instruction templates into one catalog. */
@Component
public final class AiAgentCatalog {

    private static final String AGENT_PROMPTS =
            "classpath*:ai/agent/agent-prompt-*.md";
    private static final String SYSTEM_PROMPTS =
            "classpath*:ai/system/system-prompt-*.md";
    private static final String WORKFLOW_INSTRUCTIONS =
            "classpath*:ai/workflow/*.md";
    private static final String EXECUTION_INSTRUCTIONS =
            "classpath*:ai/execution/execution-*.md";
    private static final String DEFAULT_ROOT_PROMPT =
            "classpath:ai/system/system-prompt-connect-center-assistant.md";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final Map<String, AiAgentDefinition> systemAgents;
    private final Map<String, AiAgentDefinition> workerAgents;
    private final Map<String, AgentDefinition.InstructionTemplate> workflowInstructions;
    private final Map<String, AgentDefinition.InstructionTemplate> executionInstructions;
    private final Resource rootAgentResource;

    public AiAgentCatalog(ResourceLoader resourceLoader) {
        this(resourceLoader, DEFAULT_ROOT_PROMPT);
    }

    @Autowired
    public AiAgentCatalog(ResourceLoader resourceLoader, ScoreAiProperties properties) {
        this(resourceLoader, Objects.requireNonNull(properties, "properties")
                .getAssistant().getSystemPromptResource());
    }

    private AiAgentCatalog(ResourceLoader resourceLoader, String rootPromptLocation) {
        if (!StringUtils.hasText(rootPromptLocation)) {
            throw new IllegalArgumentException("The root Agent resource must be configured.");
        }
        ResourcePatternResolver resources = new PathMatchingResourcePatternResolver(resourceLoader);
        try {
            Map<String, AiAgentDefinition> loadedSystemAgents = load(resources, SYSTEM_PROMPTS);
            Map<String, AiAgentDefinition> loadedWorkerAgents = load(resources, AGENT_PROMPTS);
            Map<String, AgentDefinition.InstructionTemplate> loadedWorkflowInstructions =
                    loadInstructions(resources, WORKFLOW_INSTRUCTIONS);
            Map<String, AgentDefinition.InstructionTemplate> loadedExecutionInstructions =
                    loadInstructions(resources, EXECUTION_INSTRUCTIONS);
            if (loadedSystemAgents.isEmpty() || loadedWorkerAgents.isEmpty()
                    || loadedWorkflowInstructions.isEmpty()
                    || loadedExecutionInstructions.isEmpty()) {
                throw new IllegalStateException(
                        "System, worker, Workflow, and execution registries are required.");
            }
            AiAgentDefinition bundledRoot = readDefinition(
                    resourceLoader.getResource(DEFAULT_ROOT_PROMPT));
            if (loadedSystemAgents.remove(bundledRoot.id()) == null) {
                throw new IllegalStateException("The bundled root Agent is not registered.");
            }
            rootAgentResource = resourceLoader.getResource(rootPromptLocation.strip());
            AiAgentDefinition configuredRoot = readDefinition(rootAgentResource);
            if (loadedSystemAgents.containsKey(configuredRoot.id())
                    || loadedWorkerAgents.containsKey(configuredRoot.id())) {
                throw new IllegalStateException(
                        "Duplicate AI agent id: " + configuredRoot.id());
            }
            ensureDisjoint(loadedSystemAgents, loadedWorkerAgents);
            systemAgents = Map.copyOf(loadedSystemAgents);
            workerAgents = Map.copyOf(loadedWorkerAgents);
            workflowInstructions = Map.copyOf(loadedWorkflowInstructions);
            executionInstructions = Map.copyOf(loadedExecutionInstructions);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not load the AI agent registry.", failure);
        }
    }

    public List<AiAgentDefinition> all() {
        Map<String, AiAgentDefinition> current = new LinkedHashMap<>(systemAgents);
        current.putAll(workerAgents);
        AiAgentDefinition root = currentRoot();
        if (current.put(root.id(), root) != null) {
            throw new IllegalStateException("Duplicate AI agent id: " + root.id());
        }
        return current.values().stream()
                .sorted(java.util.Comparator.comparing(AiAgentDefinition::id)).toList();
    }

    public AiAgentDefinition require(String id) {
        String normalized = StringUtils.hasText(id) ? id.strip().toLowerCase(Locale.ROOT) : "";
        AiAgentDefinition agent = workerAgents.get(normalized);
        if (agent == null) agent = systemAgents.get(normalized);
        if (agent == null) {
            AiAgentDefinition root = currentRoot();
            if (root.id().equals(normalized)) agent = root;
        }
        if (agent == null) throw new IllegalArgumentException("Unknown AI agent: " + id);
        return agent;
    }

    public AiAgentDefinition requireWorker(String id) {
        String normalized = StringUtils.hasText(id) ? id.strip().toLowerCase(Locale.ROOT) : "";
        AiAgentDefinition agent = workerAgents.get(normalized);
        if (agent == null) throw new IllegalArgumentException("Unknown worker Agent: " + id);
        return agent;
    }

    public AgentDefinition runtimeDefinition(String id) {
        return runtimeDefinition(require(id));
    }

    /** Returns only a Planner-addressable worker definition. */
    public AgentDefinition workerDefinition(String id) {
        return runtimeDefinition(requireWorker(id));
    }

    private AgentDefinition runtimeDefinition(AiAgentDefinition agent) {
        return new AgentDefinition(new Agent.AgentId(agent.id()), agent.name(), agent.description(),
                new AgentDefinition.InstructionTemplate(agent.instruction()));
    }

    public AgentDefinition systemDefinition(String id) {
        String normalized = StringUtils.hasText(id) ? id.strip().toLowerCase(Locale.ROOT) : "";
        AiAgentDefinition agent = systemAgents.get(normalized);
        if (agent == null) throw new IllegalArgumentException("Unknown system Agent: " + id);
        return runtimeDefinition(agent);
    }

    public AgentDefinition configuredRootDefinition() {
        return runtimeDefinition(currentRoot());
    }

    public AgentDefinition.InstructionTemplate workflowInstruction(String id) {
        String normalized = normalizeId(id);
        AgentDefinition.InstructionTemplate instruction = workflowInstructions.get(normalized);
        if (instruction == null) {
            throw new IllegalArgumentException("Unknown AI instruction: " + id);
        }
        return instruction;
    }

    public List<String> workflowInstructionIds() {
        return workflowInstructions.keySet().stream().sorted().toList();
    }

    public AgentDefinition.InstructionTemplate executionInstruction(String id) {
        String normalized = normalizeId(id);
        AgentDefinition.InstructionTemplate instruction = executionInstructions.get(normalized);
        if (instruction == null) {
            throw new IllegalArgumentException("Unknown AI execution instruction: " + id);
        }
        return instruction;
    }

    public List<String> executionInstructionIds() {
        return executionInstructions.keySet().stream().sorted().toList();
    }

    public List<AiAgentDefinition> workers() {
        return workerAgents.values().stream()
                .sorted(java.util.Comparator.comparing(AiAgentDefinition::id)).toList();
    }

    public AiAgentDefinition defaultAgent() {
        AiAgentDefinition agent = workerAgents.get("general-purpose");
        return agent != null ? agent : workers().getFirst();
    }

    public String plannerRegistry() {
        return workers().stream().map(agent -> "- %s: %s".formatted(agent.id(), agent.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private Map<String, AiAgentDefinition> load(
            ResourcePatternResolver resources, String pattern) throws IOException {
        Map<String, AiAgentDefinition> loaded = new LinkedHashMap<>();
        for (Resource resource : resources.getResources(pattern)) {
            AiAgentDefinition definition = readDefinition(resource);
            AiAgentDefinition previous = loaded.put(definition.id(), definition);
            if (previous != null) {
                throw new IllegalStateException("Duplicate AI agent id: " + definition.id());
            }
        }
        return loaded;
    }

    private Map<String, AgentDefinition.InstructionTemplate> loadInstructions(
            ResourcePatternResolver resources, String pattern) throws IOException {
        Map<String, AgentDefinition.InstructionTemplate> loaded = new LinkedHashMap<>();
        for (Resource resource : resources.getResources(pattern)) {
            String filename = resource.getFilename();
            if (!StringUtils.hasText(filename) || !filename.endsWith(".md")) {
                throw invalid(resource, "instruction filename must end in .md");
            }
            String id = filename.substring(0, filename.length() - 3)
                    .toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9][a-z0-9-]{1,63}")) {
                throw invalid(resource, "invalid instruction id");
            }
            AgentDefinition.InstructionTemplate previous = loaded.put(id,
                    new AgentDefinition.InstructionTemplate(readText(resource)));
            if (previous != null) {
                throw new IllegalStateException("Duplicate AI instruction id: " + id);
            }
        }
        return loaded;
    }

    private void ensureDisjoint(Map<String, AiAgentDefinition> system,
                                Map<String, AiAgentDefinition> workers) {
        system.keySet().stream().filter(workers::containsKey).findFirst().ifPresent(id -> {
            throw new IllegalStateException("Duplicate AI agent id: " + id);
        });
    }

    private AiAgentDefinition currentRoot() {
        try {
            AiAgentDefinition root = readDefinition(rootAgentResource);
            if (systemAgents.containsKey(root.id()) || workerAgents.containsKey(root.id())) {
                throw new IllegalStateException("Duplicate AI agent id: " + root.id());
            }
            return root;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not reload the configured root Agent definition.", failure);
        }
    }

    private AiAgentDefinition readDefinition(Resource resource) throws IOException {
        AiAgentFile agentFile = read(resource);
        AiAgentDescriptor descriptor = agentFile.descriptor();
        validate(descriptor);
        String id = descriptor.id().strip().toLowerCase(Locale.ROOT);
        return new AiAgentDefinition(id, descriptor.name().strip(),
                descriptor.description().strip(), agentFile.instruction());
    }

    private AiAgentFile read(Resource resource) throws IOException {
        String markdown = readText(resource);
        if (!markdown.startsWith("---\n")) {
            throw invalid(resource, "missing opening YAML frontmatter delimiter");
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) throw invalid(resource, "missing closing YAML frontmatter delimiter");
        AiAgentDescriptor descriptor;
        try {
            descriptor = YAML.readValue(markdown.substring(4, end), AiAgentDescriptor.class);
        } catch (IOException failure) {
            throw invalid(resource, "invalid YAML frontmatter", failure);
        }
        String instruction = markdown.substring(end + 5).strip();
        if (!StringUtils.hasText(instruction)) throw invalid(resource, "empty instruction body");
        return new AiAgentFile(descriptor, instruction);
    }

    private String readText(Resource resource) throws IOException {
        String value;
        try (var input = resource.getInputStream()) {
            value = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
        }
        if (value.startsWith("\uFEFF")) value = value.substring(1);
        return value.strip();
    }

    private String normalizeId(String id) {
        return StringUtils.hasText(id) ? id.strip().toLowerCase(Locale.ROOT) : "";
    }

    private void validate(AiAgentDescriptor descriptor) {
        if (descriptor == null || !StringUtils.hasText(descriptor.id())
                || !descriptor.id().matches("[a-z0-9][a-z0-9-]{1,63}")
                || !StringUtils.hasText(descriptor.name())
                || !StringUtils.hasText(descriptor.description())) {
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
}
