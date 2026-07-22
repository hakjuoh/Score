package org.oagi.score.gateway.http.api.ai_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.service.AiChatExecutor;
import org.oagi.score.gateway.http.api.ai_management.workflow.ChainWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.Workflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiWorkflowCompilerTest {

    @Test
    void compilesARegisteredExtensionWithoutChangingTheCompiler() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiWorkflowCompiler.WorkflowNodeCompiler extension =
                    new AiWorkflowCompiler.WorkflowNodeCompiler() {
                        @Override
                        public String workflowType() {
                            return "custom_chain";
                        }

                        @Override
                        public Workflow compile(
                                AiWorkflowNode node,
                                AiWorkflowCompiler.CompilationContext context) {
                            return new ChainWorkflow(node.id(), context.compileChildren(node));
                        }
                    };
            AiWorkflowCompiler compiler = compiler(executor, List.of(extension));
            AiWorkflowNode root = node("root", "custom_chain",
                    List.of(node("leaf", "direct", List.of())));

            WorkflowResult result = compiler.compile(root).process(executionContext());

            assertThat(result.workflowId()).isEqualTo("root");
            assertThat(result.output()).isEqualTo("executed:leaf");
            assertThat(result.children()).extracting(WorkflowResult::workflowId)
                    .containsExactly("leaf");
        }
    }

    @Test
    void rejectsUnknownAndDuplicateWorkflowCompilers() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AiWorkflowCompiler compiler = compiler(executor, List.of());
            assertThatThrownBy(() -> compiler.compile(node("root", "unknown", List.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown");

            AiWorkflowCompiler.WorkflowNodeCompiler duplicate =
                    new AiWorkflowCompiler.WorkflowNodeCompiler() {
                        @Override
                        public String workflowType() {
                            return "direct";
                        }

                        @Override
                        public Workflow compile(
                                AiWorkflowNode node,
                                AiWorkflowCompiler.CompilationContext context) {
                            return context.compileChildren(node).getFirst();
                        }
                    };
            assertThatThrownBy(() -> compiler(executor, List.of(duplicate)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already registered");
        }
    }

    private AiWorkflowCompiler compiler(
            ExecutorService executor,
            List<AiWorkflowCompiler.WorkflowNodeCompiler> extensions) {
        return new AiWorkflowCompiler(
                executor, Duration.ofSeconds(1),
                (context, node) -> WorkflowResult.success(
                        node.id(), "executed:" + node.id(), Map.of(), List.of()),
                (context, node, results) -> WorkflowResult.success(
                        node.id(), results.getLast().output(), Map.of(), results),
                extensions);
    }

    private AiWorkflowNode node(String id, String workflow, List<AiWorkflowNode> children) {
        return new AiWorkflowNode(id, workflow, false, null,
                null, null, null, null, null,
                null, null, children, Map.of());
    }

    private WorkflowContext executionContext() {
        return WorkflowContext.root(mock(AiChatExecutor.Context.class));
    }
}
