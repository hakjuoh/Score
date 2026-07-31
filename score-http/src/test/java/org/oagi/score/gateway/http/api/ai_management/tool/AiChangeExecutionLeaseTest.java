package org.oagi.score.gateway.http.api.ai_management.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.oagi.score.gateway.http.api.ai_management.agent.WorkflowRunControl;
import org.oagi.score.gateway.http.api.ai_management.service.AiRequestRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChangeExecutionLeaseTest {

    @Mock
    private AiRequestRegistry requests;

    @Mock
    private WorkflowRunControl runControl;

    @Test
    void rejectedRequestBalancesWorkflowActivityWithoutRegisteringAChange() {
        when(requests.changeStarted("request-1")).thenReturn(false);
        AiChangeExecutionLease lease = new AiChangeExecutionLease(
                "request-1", requests, runControl);

        assertThat(lease.acquire("tool:arguments")).isFalse();

        verify(runControl).definiteActivityStarted();
        verify(runControl).definiteActivityFinished();
        verify(requests, never()).changeFinished("request-1");
    }

    @Test
    void repeatedExecutionKeyRetainsAndReleasesEverySuccessfulLeaseExactlyOnce() {
        when(requests.changeStarted("request-1")).thenReturn(true);
        AiChangeExecutionLease lease = new AiChangeExecutionLease(
                "request-1", requests, runControl);

        assertThat(lease.acquire("tool:arguments")).isTrue();
        assertThat(lease.acquire("tool:arguments")).isTrue();
        lease.release("tool:arguments");
        lease.release("tool:arguments");
        lease.release("tool:arguments");

        verify(requests, times(2)).changeStarted("request-1");
        verify(requests, times(2)).changeFinished("request-1");
        verify(runControl, times(2)).definiteActivityStarted();
        verify(runControl, times(2)).definiteActivityFinished();
    }

    @Test
    void registryFailureStillBalancesWorkflowActivity() {
        doThrow(new IllegalStateException("registry unavailable"))
                .when(requests).changeStarted("request-1");
        AiChangeExecutionLease lease = new AiChangeExecutionLease(
                "request-1", requests, runControl);

        assertThatThrownBy(() -> lease.acquire("tool:arguments"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry unavailable");

        verify(runControl).definiteActivityStarted();
        verify(runControl).definiteActivityFinished();
        verify(requests, never()).changeFinished("request-1");
    }
}
