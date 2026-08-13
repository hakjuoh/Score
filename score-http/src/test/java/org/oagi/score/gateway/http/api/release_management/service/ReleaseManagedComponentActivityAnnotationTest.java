package org.oagi.score.gateway.http.api.release_management.service;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivity;
import org.oagi.score.gateway.http.api.activity_management.annotation.ScoreActivityHandlerBinding;
import org.oagi.score.gateway.http.api.activity_management.service.ScoreActivityHandler;
import org.oagi.score.gateway.http.api.agency_id_management.model.AgencyIdListManifestId;
import org.oagi.score.gateway.http.api.agency_id_management.service.AgencyIdListCommandService;
import org.oagi.score.gateway.http.api.cc_management.model.CcState;
import org.oagi.score.gateway.http.api.cc_management.model.dt.DtManifestId;
import org.oagi.score.gateway.http.api.cc_management.service.CcCommandService;
import org.oagi.score.gateway.http.api.cc_management.service.activity.CoreComponentActivityHandler;
import org.oagi.score.gateway.http.api.code_list_management.model.CodeListManifestId;
import org.oagi.score.gateway.http.api.code_list_management.service.CodeListCommandService;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseManagedComponentActivityAnnotationTest {

    @Test
    void everyReleaseManagedComponentStateEntryPointUsesTheSharedHandler() throws Exception {
        assertStateActivity(CcCommandService.class, "dt", DtManifestId.class);
        assertStateActivity(CodeListCommandService.class, "code-list", CodeListManifestId.class);
        assertStateActivity(
                AgencyIdListCommandService.class, "agency-id-list", AgencyIdListManifestId.class);
    }

    private static void assertStateActivity(
            Class<?> serviceType,
            String category,
            Class<?> manifestIdType) throws Exception {
        ScoreActivity activity = serviceType
                .getMethod("updateState", ScoreUser.class, manifestIdType, CcState.class)
                .getAnnotation(ScoreActivity.class);

        assertThat(activity).isNotNull();
        assertThat(activity.category()).isEqualTo(category);
        assertThat(activity.action()).isEqualTo("state-change");
        if (activity.handler() == ScoreActivityHandler.class) {
            ScoreActivityHandlerBinding binding = serviceType.getAnnotation(
                    ScoreActivityHandlerBinding.class);
            assertThat(binding).isNotNull();
            assertThat(binding.value()).isEqualTo(CoreComponentActivityHandler.class);
        } else {
            assertThat(activity.handler()).isEqualTo(CoreComponentActivityHandler.class);
        }
    }
}
