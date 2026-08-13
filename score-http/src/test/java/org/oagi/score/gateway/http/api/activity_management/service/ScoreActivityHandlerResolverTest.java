package org.oagi.score.gateway.http.api.activity_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivityHandlerBinding;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreActivityHandlerResolverTest {

    private final ScoreActivityHandlerResolver resolver = new ScoreActivityHandlerResolver();

    @Test
    void usesTheMethodHandlerBeforeTheClassBinding() throws Exception {
        assertThat(resolve(BoundService.class, "overridden"))
                .isEqualTo(MethodHandler.class);
    }

    @Test
    void fallsBackToTheClassBinding() throws Exception {
        assertThat(resolve(BoundService.class, "inherited"))
                .isEqualTo(BoundHandler.class);
    }

    @Test
    void rejectsAnActivityWithoutEitherHandlerSource() throws Exception {
        Method method = UnboundService.class.getDeclaredMethod("run");
        ScoreActivity activity = method.getAnnotation(ScoreActivity.class);

        assertThatThrownBy(() -> resolver.resolve(UnboundService.class, activity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No SCORE activity handler")
                .hasMessageContaining("test.run")
                .hasMessageContaining(ScoreActivityHandlerBinding.class.getSimpleName());
    }

    private Class<? extends ScoreActivityHandler> resolve(Class<?> type, String methodName)
            throws Exception {
        ScoreActivity activity = type.getDeclaredMethod(methodName).getAnnotation(ScoreActivity.class);
        return resolver.resolve(type, activity);
    }

    @ScoreActivityHandlerBinding(BoundHandler.class)
    private static class BoundService {

        @ScoreActivity(category = "test", action = "inherited")
        void inherited() {
        }

        @ScoreActivity(category = "test", action = "overridden", handler = MethodHandler.class)
        void overridden() {
        }
    }

    private static class UnboundService {

        @ScoreActivity(category = "test", action = "run")
        void run() {
        }
    }

    private static final class BoundHandler extends StubHandler {
    }

    private static final class MethodHandler extends StubHandler {
    }

    private abstract static class StubHandler implements ScoreActivityHandler {
        @Override
        public boolean supports(ScoreActivityInvocation invocation) {
            return true;
        }

        @Override
        public ScoreActivityExecution start(ScoreActivityInvocation invocation) {
            throw new UnsupportedOperationException("Resolver tests never start handlers");
        }
    }
}
