package org.oagi.score.gateway.http.api.activity_management.service;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityException;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityFailureCode;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityInvocation;
import org.oagi.score.gateway.http.api.activity_management.trace.ScoreActivityTracing;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;

/** Applies the common best-effort success/failure publication policy to annotated operations. */
@Aspect
public final class ScoreActivityAspect implements Ordered {

    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    private final ApplicationContext applicationContext;
    private final ScoreActivityEventPublisher publisher;
    private final ScoreActivityEventRecorder recorder;
    private final ScoreActivityTracing tracing;
    private final ScoreActivityHandlerResolver handlerResolver = new ScoreActivityHandlerResolver();

    public ScoreActivityAspect(
            ApplicationContext applicationContext,
            ScoreActivityEventPublisher publisher,
            ScoreActivityEventRecorder recorder) {
        this(applicationContext, publisher, recorder, new ScoreActivityTracing());
    }

    public ScoreActivityAspect(
            ApplicationContext applicationContext,
            ScoreActivityEventPublisher publisher,
            ScoreActivityEventRecorder recorder,
            ScoreActivityTracing tracing) {
        this.applicationContext = applicationContext;
        this.publisher = publisher;
        this.recorder = recorder;
        this.tracing = tracing;
    }

    @Around("@annotation(activity)")
    public Object record(ProceedingJoinPoint joinPoint, ScoreActivity activity) throws Throwable {
        Class<? extends ScoreActivityHandler> handlerType = handlerResolver.resolve(
                AopUtils.getTargetClass(joinPoint.getTarget()), activity);
        if (!eventDeliveryEnabled()) {
            return joinPoint.proceed();
        }
        ScoreActivityInvocation invocation = new ScoreActivityInvocation(
                activity.category(), activity.action(), activity.operation(),
                Arrays.asList(joinPoint.getArgs().clone()));
        try (ScoreActivityTracing.ActivitySpan activitySpan = tracing.start(invocation)) {
            ScoreActivityExecution execution = start(handlerType, invocation);
            try {
                Object result = joinPoint.proceed();
                if (execution != null) {
                    Boolean successful = recorder.capture(() -> execution.isSuccessful(result));
                    if (Boolean.FALSE.equals(successful)) {
                        activitySpan.failed(null);
                    } else {
                        activitySpan.succeeded();
                    }
                    Context activityContext = Context.current();
                    recorder.record(() -> publishCompleted(execution, result, activityContext));
                } else {
                    activitySpan.succeeded();
                }
                return result;
            } catch (Throwable failure) {
                activitySpan.failed(failure);
                if (execution != null) {
                    recorder.record(() -> publish(execution.failed(failure)));
                }
                throw failure;
            }
        }
    }

    private ScoreActivityExecution start(
            Class<? extends ScoreActivityHandler> handlerType,
            ScoreActivityInvocation invocation) {
        return recorder.capture(() -> {
            ScoreActivityHandler handler = applicationContext.getBean(handlerType);
            if (!handler.supports(invocation)) {
                throw new IllegalStateException("Activity handler '" + handler.getClass().getSimpleName()
                        + "' does not support '" + invocation.name() + "'");
            }
            ScoreActivityExecution execution = handler.start(invocation);
            if (execution == null) {
                throw new IllegalStateException("Activity handlers must not return a null execution");
            }
            return execution;
        });
    }

    private boolean eventDeliveryEnabled() {
        return Boolean.TRUE.equals(recorder.capture(publisher::isEnabled));
    }

    private void publish(List<ScoreActivityEvent> events) {
        if (events == null) {
            throw new IllegalStateException("Activity executions must not return null");
        }
        ScoreActivityEventSpanIds.unique(events).forEach(this::publish);
    }

    private void publishCompleted(
            ScoreActivityExecution execution,
            Object result,
            Context activityContext) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        recorder.record(() -> publishSucceeded(execution, result, activityContext));
                    } else {
                        recorder.record(() -> publishRolledBack(execution, activityContext));
                    }
                }
            });
            return;
        }
        publishSucceeded(execution, result, activityContext);
    }

    private void publishSucceeded(
            ScoreActivityExecution execution,
            Object result,
            Context activityContext) {
        try (Scope ignored = activityContext.makeCurrent()) {
            publish(execution.succeeded(result));
        }
    }

    private void publishRolledBack(
            ScoreActivityExecution execution,
            Context activityContext) {
        try (Scope ignored = activityContext.makeCurrent()) {
            publish(execution.failed(new ScoreActivityException(
                    ScoreActivityFailureCode.INTERNAL_ERROR,
                    "The surrounding transaction did not commit.")));
        }
    }

    private void publish(ScoreActivityEvent event) {
        // This aspect has already gated normal results on transaction completion. Registering a
        // second synchronization here can happen too late (inside afterCompletion) and lose the
        // event, so both committed successes and failures are now safe to enqueue immediately.
        publisher.publishImmediately(event);
    }

    @Override
    public int getOrder() {
        // Wrap the transaction advisor so success is observed only after commit completes.
        return ORDER;
    }
}
