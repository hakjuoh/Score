package org.oagi.score.gateway.http.api.ai_management.controller.payload;

public record ChatAttachment(String name, String mediaType, String data, Long size) {}
