package org.oagi.score.gateway.http.api.ai_management.controller.payload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUiRouteManifestTest {

    @Test
    void canonicalizesRoutesVariantsAndQueryMetadata() {
        AiUiRouteManifest manifest = new AiUiRouteManifest(1, List.of(
                new AiUiRouteManifest.Route(
                        "core-component", "/core_component",
                        Map.of("ASCCP", "/core_component/asccp/{manifestId}",
                                "ACC", "/core_component/acc/{manifestId}"),
                        List.of("manifestId", "id"), List.of("den", "manifestId"), null),
                new AiUiRouteManifest.Route(
                        "business-context", "/context_management/business_context",
                        Map.of("default", "/context_management/business_context/{id}"),
                        List.of("biz_ctx_id", "id"), List.of("name", "biz_ctx_id"),
                        new AiUiRouteManifest.ListQuery(
                                "base64-utf8-form",
                                Map.of("pageSize", "10", "pageIndex", "0"),
                                List.of("name", "pageSize"),
                                Map.of("biz_ctx_name", "name", "limit", "pageSize"), Map.of()))));

        assertThat(manifest.promptText())
                .startsWith("schemaVersion=1\n- resource=business-context")
                .contains("details=default=/context_management/business_context/{id}")
                .contains("resource=core-component")
                .contains("details=ACC=/core_component/acc/{manifestId}"
                        + "|ASCCP=/core_component/asccp/{manifestId}")
                .contains("listQuery=codec=base64-utf8-form")
                .doesNotContain("formatterRule");
        assertThat(manifest.promptCacheKey())
                .matches("connectcenter-ui-routes-v1-[0-9a-f]{32}");
        assertThat(manifest.promptCacheKey()).isEqualTo(manifest.promptCacheKey());
    }

    @Test
    void rejectsUnsupportedSchemasAndUnsafePaths() {
        assertThatThrownBy(() -> new AiUiRouteManifest(2, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");

        assertThatThrownBy(() -> new AiUiRouteManifest(1, List.of(
                new AiUiRouteManifest.Route(
                        "business-context", "//outside.example/path", Map.of(),
                        List.of("id"), List.of("name"), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listPath");

        assertThatThrownBy(() -> new AiUiRouteManifest(1, List.of(
                new AiUiRouteManifest.Route(
                        "business-context", "/context", Map.of("default", "/context/{bad value}"),
                        List.of("id"), List.of("name"), null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detailPatterns value");
    }
}
