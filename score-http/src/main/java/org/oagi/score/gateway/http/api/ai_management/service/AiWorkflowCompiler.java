package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowNode;
import org.oagi.score.gateway.http.api.ai_management.workflow.ChainWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.DirectWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.OrchestratorWorkersWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.ParallelizationWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.RoutingWorkflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.Workflow;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowAggregator;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowResult;
import org.oagi.score.gateway.http.api.ai_management.workflow.WorkflowTypes;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;

/** Compiles a validated recursive plan into executable workflow objects. */
public final class AiWorkflowCompiler {

    private final ExecutorService executor;
    private final Duration timeout;
    private final BiFunction<WorkflowContext, AiWorkflowNode, WorkflowResult> leafOperation;
    private final NodeAggregator aggregator;
    private final Map<String, WorkflowNodeCompiler> nodeCompilers;

    public AiWorkflowCompiler(
            ExecutorService executor, Duration timeout,
            BiFunction<WorkflowContext, AiWorkflowNode, WorkflowResult> leafOperation,
            NodeAggregator aggregator) {
        this(executor, timeout, leafOperation, aggregator, List.of());
    }

    public AiWorkflowCompiler(
            ExecutorService executor, Duration timeout,
            BiFunction<WorkflowContext, AiWorkflowNode, WorkflowResult> leafOperation,
            NodeAggregator aggregator,
            Collection<? extends WorkflowNodeCompiler> extensions) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.leafOperation = Objects.requireNonNull(leafOperation, "leafOperation");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        Map<String, WorkflowNodeCompiler> registered = new LinkedHashMap<>();
        builtInCompilers().forEach(compiler -> register(registered, compiler));
        if (extensions != null) {
            extensions.forEach(compiler -> register(registered,
                    Objects.requireNonNull(compiler, "workflow compiler")));
        }
        this.nodeCompilers = Map.copyOf(registered);
    }

    public Workflow compile(AiWorkflowNode node) {
        Objects.requireNonNull(node, "node");
        WorkflowNodeCompiler compiler = nodeCompilers.get(node.workflow());
        if (compiler == null) {
            throw new IllegalArgumentException(
                    "Unknown workflow type '" + node.workflow() + "'.");
        }
        return Objects.requireNonNull(compiler.compile(node, new DefaultCompilationContext()),
                () -> "Workflow compiler '" + node.workflow() + "' returned null.");
    }

    private List<Workflow> compileChildren(AiWorkflowNode node) {
        return node.children().stream().map(this::compile).toList();
    }

    private Map<String, Workflow> compileRoutes(AiWorkflowNode node) {
        Map<String, Workflow> result = new LinkedHashMap<>();
        node.routes().forEach((route, child) -> result.put(route, compile(child)));
        return Map.copyOf(result);
    }

    private WorkflowAggregator aggregate(AiWorkflowNode node) {
        return (context, results) -> aggregator.aggregate(context, node, results);
    }

    private List<WorkflowNodeCompiler> builtInCompilers() {
        return List.of(
                compiler(WorkflowTypes.DIRECT, (node, context) -> new DirectWorkflow(node.id(),
                        execution -> context.executeLeaf(execution, node))),
                compiler(WorkflowTypes.CHAIN, (node, context) -> new ChainWorkflow(
                        node.id(), context.compileChildren(node))),
                compiler(WorkflowTypes.PARALLEL, (node, context) -> new ParallelizationWorkflow(
                        node.id(), context.compileChildren(node), context.executor(),
                        context.timeout(), context.aggregator(node))),
                compiler(WorkflowTypes.ROUTING, (node, context) -> new RoutingWorkflow(
                        node.id(), ignored -> node.selectedRoute(), context.compileRoutes(node))),
                compiler(WorkflowTypes.ORCHESTRATOR_WORKERS, (node, context) ->
                        new OrchestratorWorkersWorkflow(node.id(),
                                ignored -> context.compileChildren(node), context.executor(),
                                context.timeout(), context.aggregator(node))));
    }

    private WorkflowNodeCompiler compiler(
            String workflowType,
            BiFunction<AiWorkflowNode, CompilationContext, Workflow> operation) {
        return new WorkflowNodeCompiler() {
            @Override
            public String workflowType() {
                return workflowType;
            }

            @Override
            public Workflow compile(AiWorkflowNode node, CompilationContext context) {
                return operation.apply(node, context);
            }
        };
    }

    private void register(Map<String, WorkflowNodeCompiler> registered,
                          WorkflowNodeCompiler compiler) {
        String workflowType = Objects.requireNonNull(
                compiler.workflowType(), "workflowType").strip();
        if (workflowType.isEmpty()) {
            throw new IllegalArgumentException("A workflow compiler type cannot be empty.");
        }
        if (registered.putIfAbsent(workflowType, compiler) != null) {
            throw new IllegalArgumentException(
                    "A workflow compiler is already registered for '" + workflowType + "'.");
        }
    }

    private final class DefaultCompilationContext implements CompilationContext {

        @Override
        public Workflow compile(AiWorkflowNode node) {
            return AiWorkflowCompiler.this.compile(node);
        }

        @Override
        public List<Workflow> compileChildren(AiWorkflowNode node) {
            return AiWorkflowCompiler.this.compileChildren(node);
        }

        @Override
        public Map<String, Workflow> compileRoutes(AiWorkflowNode node) {
            return AiWorkflowCompiler.this.compileRoutes(node);
        }

        @Override
        public ExecutorService executor() {
            return executor;
        }

        @Override
        public Duration timeout() {
            return timeout;
        }

        @Override
        public WorkflowResult executeLeaf(WorkflowContext context, AiWorkflowNode node) {
            return leafOperation.apply(context, node);
        }

        @Override
        public WorkflowAggregator aggregator(AiWorkflowNode node) {
            return aggregate(node);
        }
    }

    /** Extension point for adding a workflow node type without changing the compiler. */
    public interface WorkflowNodeCompiler {

        String workflowType();

        Workflow compile(AiWorkflowNode node, CompilationContext context);
    }

    /** Restricted recursive compiler API supplied to registered node compilers. */
    public interface CompilationContext {

        Workflow compile(AiWorkflowNode node);

        List<Workflow> compileChildren(AiWorkflowNode node);

        Map<String, Workflow> compileRoutes(AiWorkflowNode node);

        ExecutorService executor();

        Duration timeout();

        WorkflowResult executeLeaf(WorkflowContext context, AiWorkflowNode node);

        WorkflowAggregator aggregator(AiWorkflowNode node);
    }

    @FunctionalInterface
    public interface NodeAggregator {
        WorkflowResult aggregate(WorkflowContext context, AiWorkflowNode node,
                                 List<WorkflowResult> results);
    }
}
