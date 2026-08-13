package org.oagi.score.gateway.http.api.cc_management.service.activity;

import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityContext;
import org.oagi.score.gateway.http.api.activity_management.model.ScoreActivityTarget;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityEventFactory;
import org.oagi.score.gateway.http.api.cc_management.model.ManifestId;
import org.oagi.score.gateway.http.common.model.Guid;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class CoreComponentActivityTestSupport {

    private CoreComponentActivityTestSupport() {
    }

    static CoreComponentActivityEventFactory eventFactory() {
        CoreComponentActivityTargetResolver resolver = mock(CoreComponentActivityTargetResolver.class);
        when(resolver.resolve(anyString(), any(), any())).thenAnswer(invocation -> {
            String category = invocation.getArgument(0);
            ManifestId manifestId = invocation.getArgument(2);
            return ScoreActivityTarget.primary(
                    targetType(category), manifestId,
                    new Guid("0123456789abcdef0123456789abcdef"), category + " name");
        });
        when(resolver.resolveOrReference(anyString(), any(), any())).thenAnswer(invocation -> {
            String category = invocation.getArgument(0);
            ManifestId manifestId = invocation.getArgument(2);
            return ScoreActivityTarget.primary(
                    targetType(category), manifestId,
                    new Guid("0123456789abcdef0123456789abcdef"), category + " name");
        });
        return new CoreComponentActivityEventFactory(
                new ScoreActivityEventFactory(Clock.systemUTC(), ScoreActivityContext::empty),
                resolver);
    }

    private static String targetType(String category) {
        return switch (category) {
            case "code-list" -> "CODE_LIST";
            case "agency-id-list" -> "AGENCY_ID_LIST";
            case "dt-sc" -> "DT_SC";
            default -> category.toUpperCase();
        };
    }
}
