package org.oagi.score.gateway.http.api.ai_management;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Locks the protocol-neutral seams introduced by the modular AI migration. */
@AnalyzeClasses(
        packages = "org.oagi.score.gateway.http.api.ai_management",
        importOptions = ImportOption.DoNotIncludeTests.class,
        cacheMode = CacheMode.PER_CLASS)
class AiModularArchitectureTest {

    private static final Set<String> COMPATIBILITY_ADAPTERS = Set.of(
            "org.oagi.score.gateway.http.api.ai_management.conversation.AiChatRetentionService",
            "org.oagi.score.gateway.http.api.ai_management.tool.AiMutationToolGuard",
            "org.oagi.score.gateway.http.api.ai_management.tool.AiToolFailureMessage",
            "org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowEvaluator",
            "org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowExecutionCoordinator",
            "org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowIntent",
            "org.oagi.score.gateway.http.api.ai_management.workflow.AiWorkflowPlanner");

    private static final DescribedPredicate<JavaClass> PROTOCOL_NEUTRAL_TYPES =
            DescribedPredicate.describe("established protocol-neutral AI types", type -> {
                String packageName = type.getPackageName();
                boolean semanticPackage = packageName.endsWith(".agent")
                        || packageName.endsWith(".guardrail")
                        || packageName.endsWith(".conversation")
                        || packageName.endsWith(".tool")
                        || packageName.endsWith(".workflow");
                return semanticPackage && !COMPATIBILITY_ADAPTERS.contains(topLevelName(type));
            });

    private static String topLevelName(JavaClass type) {
        int nestedType = type.getName().indexOf('$');
        return nestedType >= 0 ? type.getName().substring(0, nestedType) : type.getName();
    }

    @ArchTest
    static final ArchRule protocol_neutral_types_do_not_import_spring_ai_or_mcp =
            noClasses().that(PROTOCOL_NEUTRAL_TYPES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.ai..", "io.modelcontextprotocol..")
                    .because("Spring AI and MCP types belong behind execution/provider adapters");

    @ArchTest
    static final ArchRule protocol_neutral_types_do_not_import_transport_or_persistence =
            noClasses().that(PROTOCOL_NEUTRAL_TYPES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..ai_management.controller..",
                            "..ai_management.repository..",
                            "org.jooq..")
                    .because("established Agent, Guardrail, Tool, Workflow, and Conversation "
                            + "contracts must not depend on HTTP or persistence adapters");

    @ArchTest
    static final ArchRule workflow_implementations_do_not_construct_model_clients =
            noClasses().that().resideInAPackage("..ai_management.workflow..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "org.springframework.ai.chat.client.ChatClient")
                    .because("Workflows compose Agent runs through the execution port");

    @ArchTest
    static final ArchRule application_services_do_not_call_provider_specific_model_sdks =
            noClasses().that().resideInAPackage("..ai_management.service..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.ai.ollama.api..",
                            "org.springframework.ai.openai.api..",
                            "org.springframework.ai.anthropic.api..")
                    .because("all application inference enters through AgentExecutionService");
}
