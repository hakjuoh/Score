package org.oagi.score.gateway.http.api.ai_management.execution;

import org.oagi.score.gateway.http.api.ai_management.tool.AiChangeToolGuard;
import org.oagi.score.gateway.http.api.ai_management.tool.AiToolInputNormalizer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Optional;

/** Spring AI callback adapter for approval and execution fencing. */
public final class AiGuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AiChangeToolGuard.GuardedToolSession session;
    private final AiToolInputNormalizer inputNormalizer;

    public AiGuardedToolCallback(ToolCallback delegate,
                                 AiChangeToolGuard.GuardedToolSession session,
                                 AiToolInputNormalizer inputNormalizer) {
        this.delegate = delegate;
        this.session = session;
        this.inputNormalizer = inputNormalizer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String input) {
        return call(input, new ToolContext(java.util.Map.of()));
    }

    @Override
    public String call(String input, ToolContext context) {
        String name = getToolDefinition().name();
        String normalizedInput = inputNormalizer.normalize(
                input, getToolDefinition().inputSchema());
        if (!session.isChange(name)) {
            String result = delegate.call(normalizedInput, context);
            session.readCompleted();
            return result;
        }
        Optional<String> cached = session.cachedResult(name, normalizedInput);
        if (cached.isPresent()) return cached.get();
        Optional<String> refusal = session.approvalRefusal(name, normalizedInput);
        if (refusal.isPresent()) return refusal.get();
        if (!session.startChange(name, normalizedInput)) {
            return AiChangeToolGuard.requestStoppingResult();
        }
        try {
            String result = delegate.call(normalizedInput, context);
            session.changeCompleted(name, normalizedInput, result);
            return result;
        } finally {
            session.releaseChangeLease(name, normalizedInput);
        }
    }
}
