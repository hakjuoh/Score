package org.oagi.score.gateway.http.api.bie_management.model.asbiep;

public record AsbiepSupportDocRecord(
        AsbiepSupportDocId asbiepSupportDocId,
        AsbiepId asbiepId,
        String content,
        String description) {

}
