package org.oagi.score.gateway.http.api.bie_management.model.bie_package;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude
public record BieComponentChange(String componentName,
                                 String parentComponentPath,
                                 List<String> changes) {
}
