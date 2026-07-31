package org.oagi.score.gateway.http.api.ai_management.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiChangeToolResultsTest {

    @Test
    void nestedInfrastructureFailureIsNeverReturnedToTheModel() {
        AiChangeToolResults results = new AiChangeToolResults(new ObjectMapper());
        RuntimeException failure = new IllegalStateException(
                "change failed", new RuntimeException(
                "jdbc:postgresql://internal/db password=hunter2 SELECT secret FROM users"));

        assertThat(results.changeFailed(failure))
                .contains(AiToolFailureMessage.GENERIC_MESSAGE)
                .doesNotContain("jdbc")
                .doesNotContain("hunter2")
                .doesNotContain("SELECT")
                .doesNotContain("internal");
    }
}
