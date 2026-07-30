package org.oagi.score.gateway.http.api.ai_management.tool.file;

import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileRequestDetector;
import org.oagi.score.gateway.http.api.ai_management.tool.file.MarkdownFileRenderer;
import org.oagi.score.gateway.http.api.ai_management.tool.file.PdfFileRenderer;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiFileRequestDetectorTest {

    private final AiFileRequestDetector detector = new AiFileRequestDetector(
            List.of(new MarkdownFileRenderer(), new PdfFileRenderer(new ScoreAiProperties())));

    @Test
    void detectsExplicitFormatsAndRequestedFilename() {
        assertThat(detector.detect("Create a Markdown report and save it as release-notes.md"))
                .containsExactly(new AiFileRequestDetector.Request("markdown", "release-notes.md"));
        assertThat(detector.detect("Generate this report as a downloadable PDF."))
                .containsExactly(new AiFileRequestDetector.Request("pdf", null));
    }

    @Test
    void ignoresMentionsWithoutOutputIntentAndNegatedRequests() {
        assertThat(detector.detect("Explain how PDF rendering works.")).isEmpty();
        assertThat(detector.detect("Do not create a PDF; answer in chat.")).isEmpty();
    }
}
