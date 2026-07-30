package org.oagi.score.gateway.http.api.ai_management.artifact;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiArtifactRequestDetectorTest {

    private final AiArtifactRequestDetector detector = new AiArtifactRequestDetector(
            List.of(new MarkdownArtifactRenderer(), new PdfArtifactRenderer(new ScoreAiProperties())));

    @Test
    void detectsExplicitFormatsAndRequestedFilename() {
        assertThat(detector.detect("Create a Markdown report and save it as release-notes.md"))
                .containsExactly(new AiArtifactRequestDetector.Request("markdown", "release-notes.md"));
        assertThat(detector.detect("Generate this report as a downloadable PDF."))
                .containsExactly(new AiArtifactRequestDetector.Request("pdf", null));
    }

    @Test
    void ignoresMentionsWithoutOutputIntentAndNegatedRequests() {
        assertThat(detector.detect("Explain how PDF rendering works.")).isEmpty();
        assertThat(detector.detect("Do not create a PDF; answer in chat.")).isEmpty();
    }
}
