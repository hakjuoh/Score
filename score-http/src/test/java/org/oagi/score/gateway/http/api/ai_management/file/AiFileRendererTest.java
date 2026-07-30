package org.oagi.score.gateway.http.api.ai_management.file;

import com.fasterxml.jackson.databind.node.TextNode;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiFileRendererTest {

    @Test
    void registryResolvesStableFormatsAndAliases() {
        MarkdownFileRenderer markdown = new MarkdownFileRenderer();
        AiFileRendererRegistry registry = new AiFileRendererRegistry(List.of(markdown));

        assertThat(registry.require("MARKDOWN")).isSameAs(markdown);
        assertThat(registry.require("md")).isSameAs(markdown);
        assertThat(registry.formats()).containsExactly("markdown");
        assertThatThrownBy(() -> registry.require("xlsx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Available formats: markdown");
    }

    @Test
    void markdownRendererProducesExactUtf8Bytes() {
        var rendered = new MarkdownFileRenderer().render(
                TextNode.valueOf("# Report\n\nDone."), Map.of());

        assertThat(rendered.mediaType()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(new String(rendered.content(), StandardCharsets.UTF_8))
                .isEqualTo("# Report\n\nDone.");
    }

    @Test
    void pdfRendererProducesReadablePdfAndCanUseAnExternalAssetBase(@TempDir Path temporary) throws Exception {
        ScoreAiProperties properties = new ScoreAiProperties();
        properties.getTools().getFiles().getRendering().setAssetBaseUri(temporary.toUri().toString());
        var rendered = new PdfFileRenderer(properties).render(
                TextNode.valueOf("# Work report\n\n- First\n- Second"), Map.of());

        assertThat(rendered.mediaType()).isEqualTo("application/pdf");
        assertThat(rendered.content()).startsWith('%', 'P', 'D', 'F');
        assertThat(rendered.content().length).isGreaterThan(500);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(rendered.content()))) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Work report", "First", "Second");
        }
    }

    @Test
    void pdfAssetPolicyAllowsOnlyImagesWithinTheConfiguredBase(@TempDir Path temporary) throws Exception {
        Path assets = java.nio.file.Files.createDirectory(temporary.resolve("assets"));
        Path allowed = java.nio.file.Files.write(assets.resolve("pixel.png"), new byte[]{1});
        Path outside = java.nio.file.Files.write(temporary.resolve("secret.png"), new byte[]{1});
        URI httpsBase = URI.create("https://assets.example.test/reports/");

        assertThat(PdfFileRenderer.isAllowedAsset("data:image/png;base64,AA==",
                ExternalResourceType.IMAGE_RASTER, null)).isFalse();
        assertThat(PdfFileRenderer.isAllowedAsset(allowed.toUri().toString(),
                ExternalResourceType.IMAGE_RASTER, assets.toUri())).isTrue();
        assertThat(PdfFileRenderer.isAllowedAsset(outside.toUri().toString(),
                ExternalResourceType.IMAGE_RASTER, assets.toUri())).isFalse();
        assertThat(PdfFileRenderer.isAllowedAsset("https://assets.example.test/reports/logo.png",
                ExternalResourceType.IMAGE_RASTER, httpsBase)).isTrue();
        assertThat(PdfFileRenderer.isAllowedAsset("https://assets.example.test/other/logo.png",
                ExternalResourceType.IMAGE_RASTER, httpsBase)).isFalse();
        assertThat(PdfFileRenderer.isAllowedAsset("http://127.0.0.1/private",
                ExternalResourceType.IMAGE_RASTER, httpsBase)).isFalse();
        assertThat(PdfFileRenderer.isAllowedAsset("https://assets.example.test/reports/theme.css",
                ExternalResourceType.CSS, httpsBase)).isFalse();
    }

    @Test
    void pdfRendererNeverFetchesAnUnconfiguredRemoteImage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private.png", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String image = "http://127.0.0.1:" + server.getAddress().getPort() + "/private.png";
            var rendered = new PdfFileRenderer(new ScoreAiProperties()).render(
                    TextNode.valueOf("# Safe report\n\n![private](" + image + ")"), Map.of());

            assertThat(rendered.content()).startsWith('%', 'P', 'D', 'F');
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pdfRendererLoadsOnlyTheConfiguredRemoteRootAndDoesNotFollowRedirects() throws Exception {
        AtomicInteger allowedRequests = new AtomicInteger();
        AtomicInteger redirectedRequests = new AtomicInteger();
        HttpServer redirected = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirected.createContext("/private.png", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        redirected.start();
        HttpServer assets = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] pixel = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        assets.createContext("/assets/pixel.png", exchange -> {
            allowedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, pixel.length);
            exchange.getResponseBody().write(pixel);
            exchange.close();
        });
        assets.createContext("/assets/redirect.png", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:"
                    + redirected.getAddress().getPort() + "/private.png");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assets.start();
        try {
            ScoreAiProperties properties = new ScoreAiProperties();
            properties.getTools().getFiles().getRendering().setAssetBaseUri(
                    "http://127.0.0.1:" + assets.getAddress().getPort() + "/assets/");
            var rendered = new PdfFileRenderer(properties).render(TextNode.valueOf(
                    "# Assets\n\n![pixel](pixel.png)\n\n![redirect](redirect.png)"), Map.of());

            assertThat(rendered.content()).startsWith('%', 'P', 'D', 'F');
            assertThat(allowedRequests).hasValue(1);
            assertThat(redirectedRequests).hasValue(0);
        } finally {
            assets.stop(0);
            redirected.stop(0);
        }
    }
}
