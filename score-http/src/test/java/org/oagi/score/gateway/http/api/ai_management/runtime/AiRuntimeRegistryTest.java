package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRuntimeRegistryTest {

    @Test
    void dispatchesToTheNamedRuntime() {
        AiRuntime runtime = mock(AiRuntime.class);
        AiRuntime.Context context = mock(AiRuntime.Context.class);
        when(runtime.name()).thenReturn("openai");
        when(runtime.execute(context)).thenReturn(new AiRuntime.Result("ok"));
        AiRuntimeRegistry registry = new AiRuntimeRegistry(List.of(runtime));

        registry.execute(" OPENAI ", context);

        verify(runtime).execute(context);
    }

    @Test
    void rejectsUnknownRuntimeNames() {
        AiRuntimeRegistry registry = new AiRuntimeRegistry(List.of());

        assertThatThrownBy(() -> registry.execute("removed-runtime", mock(AiRuntime.Context.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("removed-runtime");
    }
}
