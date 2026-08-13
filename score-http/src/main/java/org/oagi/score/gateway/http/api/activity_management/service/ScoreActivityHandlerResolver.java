package org.oagi.score.gateway.http.api.activity_management.service;

import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivityHandlerBinding;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static java.util.Objects.requireNonNull;

/** Resolves method-level activity handlers before falling back to the service-class binding. */
final class ScoreActivityHandlerResolver {

    Class<? extends ScoreActivityHandler> resolve(Class<?> targetClass, ScoreActivity activity) {
        requireNonNull(targetClass, "targetClass must not be null");
        requireNonNull(activity, "activity must not be null");

        if (activity.handler() != ScoreActivityHandler.class) {
            return activity.handler();
        }
        ScoreActivityHandlerBinding binding = AnnotatedElementUtils.findMergedAnnotation(
                targetClass, ScoreActivityHandlerBinding.class);
        if (binding != null && binding.value() != ScoreActivityHandler.class) {
            return binding.value();
        }
        throw new IllegalStateException("No SCORE activity handler is configured for '"
                + activity.category() + '.' + activity.action() + "' on " + targetClass.getName()
                + ". Declare handler on @ScoreActivity or add @ScoreActivityHandlerBinding.");
    }
}
