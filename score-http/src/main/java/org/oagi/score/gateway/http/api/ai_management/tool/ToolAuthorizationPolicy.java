package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;

import java.util.Objects;

/** Optional blocking authorization policy, including exact mutation confirmation. */
@FunctionalInterface
public interface ToolAuthorizationPolicy {

    Result authorize(Request request);

    /**
     * Acquires any execution-time lease after arguments are stable and the request fence passed.
     * A mutation policy uses this hook to close the cancellation race immediately before the
     * provider is invoked.
     */
    default Result beforeExecution(Request request) {
        return new Result.Allow("authorized");
    }

    /** Records mandatory consistency state with the bounded, output-guarded result. */
    default void afterExecution(Request request, AiTool.ToolResult result) {
    }

    /** Releases an execution-time lease when the provider failed. */
    default void afterFailure(Request request, RuntimeException failure) {
    }

    /** Releases a lease when a later authorization middleware vetoes execution. */
    default void afterAborted(Request request) {
    }

    record Request(AiTool.ToolSpecification tool, AiTool.ToolArguments arguments,
                   ExecutionScope scope) {
        public Request {
            Objects.requireNonNull(tool); Objects.requireNonNull(arguments); Objects.requireNonNull(scope);
        }
    }

    sealed interface Result permits Result.Allow, Result.Refuse {
        record Allow(String grantId) implements Result {
            public Allow { grantId = Objects.requireNonNullElse(grantId, "unrestricted"); }
        }
        record Refuse(AiTool.ToolResult replacement) implements Result {
            public Refuse { Objects.requireNonNull(replacement); }
        }
    }
}
