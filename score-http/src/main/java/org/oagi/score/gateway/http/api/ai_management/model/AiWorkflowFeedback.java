package org.oagi.score.gateway.http.api.ai_management.model;

/** Bounded state carried from an evaluator decision into the next planning iteration. */
public record AiWorkflowFeedback(int iteration, String workflow, String priorResult,
                                 String feedback, String nextObjective) {}
