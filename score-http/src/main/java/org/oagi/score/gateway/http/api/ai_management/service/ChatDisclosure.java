package org.oagi.score.gateway.http.api.ai_management.service;

import java.util.Map;

/** Safe public answer and the policy evidence used to build its committed trace. */
record ChatDisclosure(String answer, Map<String, Object> metadata,
                      PublicOutputDisclosureGate.Outcome outcome) {
}
