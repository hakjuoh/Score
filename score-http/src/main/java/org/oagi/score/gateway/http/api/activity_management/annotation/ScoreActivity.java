package org.oagi.score.gateway.http.api.activity_management.annotation;

import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a user-visible SCORE action around a service operation.
 *
 * <p>This is implemented with Spring proxy AOP, so self-invocation is not advised; move a nested
 * activity boundary to a separate Spring bean. An annotated operation must also join an existing
 * transaction or run without one. Transaction propagation that suspends an existing transaction
 * ({@code REQUIRES_NEW} or {@code NOT_SUPPORTED}) is not supported because completion would be
 * associated with the resumed outer transaction.</p>
 *
 * <p>The handler may be declared on this method or inherited from the service's
 * {@link ScoreActivityHandlerBinding}. A method declaration takes precedence. Missing both is a
 * configuration error, even when activity delivery is disabled.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ScoreActivity {

    String category();

    String action();

    /**
     * Distinguishes user operations that intentionally share the same source-independent event
     * name, for example append-ASCC and reorder-association under {@code acc.update}.
     */
    String operation() default "";

    /**
     * Overrides the handler bound to the declaring class. The interface type is the sentinel for
     * "not specified" because annotation attributes cannot use {@code null}.
     */
    Class<? extends ScoreActivityHandler> handler() default ScoreActivityHandler.class;
}
