package org.oagi.score.gateway.http.api.ai_management.execution;

/** Signals that completed change operations could not be followed by a final read-back. */
public final class AiChangeReadBackException extends IllegalStateException {

    private final int completedChangeCount;

    public AiChangeReadBackException(int completedChangeCount) {
        super("The assistant stopped after a change without completing read-back.");
        if (completedChangeCount < 1) {
            throw new IllegalArgumentException("A read-back failure requires a completed change.");
        }
        this.completedChangeCount = completedChangeCount;
    }

    public int completedChangeCount() {
        return completedChangeCount;
    }
}
