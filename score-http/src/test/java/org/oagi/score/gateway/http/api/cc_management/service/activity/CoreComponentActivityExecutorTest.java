package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.account_management.model.UserId;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityEvent;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityAspect;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventPublisher;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventRecorder;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityFailureClassifier;
import org.oagi.score.gateway.http.api.cc_management.model.acc.AccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.ascc.AsccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.asccp.AsccpManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.bcc.BccManifestId;
import org.oagi.score.gateway.http.api.cc_management.model.dt_sc.DtScManifestId;
import org.oagi.score.gateway.http.common.model.Guid;
import org.oagi.score.gateway.http.common.model.ScoreRole;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class CoreComponentActivityExecutorTest {

    @Test
    void eachNestedCreateBoundaryPublishesOneCategorySpecificEvent() {
        ScoreUser requester = new ScoreUser(
                new UserId(BigInteger.valueOf(7)), "developer", "Developer", null, false,
                List.of(ScoreRole.DEVELOPER));
        CapturingPublisher publisher = new CapturingPublisher();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        CoreComponentActivityHandler handler = new CoreComponentActivityHandler(
                CoreComponentActivityTestSupport.eventFactory(),
                new ScoreActivityFailureClassifier());
        when(applicationContext.getBean(CoreComponentActivityHandler.class)).thenReturn(handler);
        CoreComponentActivityTargetResolver targetResolver = mock(CoreComponentActivityTargetResolver.class);
        when(targetResolver.resolveOrReference(anyString(), any(), any())).thenAnswer(invocation -> {
            String category = invocation.getArgument(0);
            Object id = invocation.getArgument(2);
            String type = "dt-sc".equals(category) ? "DT_SC" : category.toUpperCase();
            return ScoreActivityTarget.primary(type, id,
                    new Guid("0123456789abcdef0123456789abcdef"), category + " name");
        });
        when(targetResolver.resolve(anyString(), any(), any())).thenAnswer(invocation -> {
            String category = invocation.getArgument(0);
            Object id = invocation.getArgument(2);
            String type = "dt-sc".equals(category) ? "DT_SC" : category.toUpperCase();
            return ScoreActivityTarget.primary(type, id,
                    new Guid("0123456789abcdef0123456789abcdef"), category + " name");
        });
        CoreComponentNestedActivityHandler nestedHandler = new CoreComponentNestedActivityHandler(
                CoreComponentActivityTestSupport.eventFactory(), targetResolver,
                new ScoreActivityFailureClassifier());
        when(applicationContext.getBean(CoreComponentNestedActivityHandler.class)).thenReturn(nestedHandler);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new CoreComponentActivityExecutor());
        proxyFactory.addAspect(new ScoreActivityAspect(
                applicationContext, publisher, new ScoreActivityEventRecorder()));
        CoreComponentActivityExecutor executor = proxyFactory.getProxy();

        assertThat(executor.createAcc(requester,
                () -> new AccManifestId(BigInteger.valueOf(41)))).isNotNull();
        assertThat(executor.createAsccp(requester,
                () -> new AsccpManifestId(BigInteger.valueOf(42)))).isNotNull();
        assertThat(executor.createAscc(requester,
                () -> new AsccManifestId(BigInteger.valueOf(43)))).isNotNull();
        assertThat(executor.updateAscc(requester, new AsccManifestId(BigInteger.valueOf(43)),
                () -> true)).isTrue();
        assertThat(executor.deleteAscc(requester, new AsccManifestId(BigInteger.valueOf(43)),
                () -> true)).isTrue();
        assertThat(executor.createBcc(requester,
                () -> new BccManifestId(BigInteger.valueOf(44)))).isNotNull();
        assertThat(executor.createDtSc(requester,
                () -> new DtScManifestId(BigInteger.valueOf(45)))).isNotNull();
        assertThat(publisher.events).extracting(ScoreActivityEvent::name)
                .containsExactly("acc.create", "asccp.create",
                        "ascc.create", "ascc.update", "ascc.delete",
                        "bcc.create", "dt-sc.create");
        assertThat(publisher.events).allSatisfy(event ->
                assertThat(event.outcome()).isEqualTo(ScoreActivityEvent.SUCCEEDED));
    }

    private static final class CapturingPublisher implements ScoreActivityEventPublisher {
        private final List<ScoreActivityEvent> events = new ArrayList<>();

        @Override
        public void publish(ScoreActivityEvent event) {
            events.add(event);
        }

        @Override
        public void publishImmediately(ScoreActivityEvent event) {
            events.add(event);
        }
    }
}
