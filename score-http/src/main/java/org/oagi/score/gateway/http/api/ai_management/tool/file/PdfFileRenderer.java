package org.oagi.score.gateway.http.api.ai_management.tool.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public final class PdfFileRenderer implements AiFileRenderer {

    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;
    private final ScoreAiProperties.FileRendering configuration;

    public PdfFileRenderer(ScoreAiProperties properties) {
        MutableDataSet options = new MutableDataSet();
        options.set(HtmlRenderer.SUPPRESS_HTML, true);
        this.markdownParser = Parser.builder(options).build();
        this.htmlRenderer = HtmlRenderer.builder(options).build();
        this.configuration = properties.getTools().getFiles().getRendering();
    }

    @Override public String format() { return "pdf"; }
    @Override public Set<String> aliases() { return Set.of("application/pdf"); }
    @Override public String defaultExtension() { return "pdf"; }

    @Override
    public RenderedFile render(JsonNode content, Map<String, Object> options) {
        if (content == null || !content.isTextual() || content.textValue().isBlank()) {
            throw new IllegalArgumentException("PDF files require non-empty Markdown content.");
        }
        String body = htmlRenderer.render(markdownParser.parse(content.textValue()));
        boolean hasConfiguredFonts = configuration.getFontFiles().stream()
                .anyMatch(StringUtils::hasText);
        String bodyFontFamily = hasConfiguredFonts ? "FileSans, sans-serif" : "serif";
        String html = """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8"/>
                <style>
                @page { size: A4; margin: 20mm; }
                body { font-family: %s; font-size: 10.5pt; line-height: 1.45; color: #202124; }
                h1, h2, h3 { color: #182433; page-break-after: avoid; }
                h1 { font-size: 22pt; } h2 { font-size: 16pt; margin-top: 18pt; }
                table { border-collapse: collapse; width: 100%%; }
                th, td { border: 1px solid #c8cdd3; padding: 5pt; vertical-align: top; }
                pre { background: #f5f7f9; padding: 8pt; white-space: pre-wrap; }
                code { font-family: monospace; }
                img { max-width: 100%%; height: auto; }
                a { color: #1558a6; }
                </style></head><body>%s</body></html>
                """.formatted(bodyFontFamily, body);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            URI assetBase = assetBaseUri();
            builder.withHtmlContent(html, assetBase != null ? assetBase.toString() : null);
            builder.useExternalResourceAccessControl(
                    (uri, type) -> isAllowedAsset(uri, type, assetBase),
                    ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            RestrictedAssetFactory assetFactory = new RestrictedAssetFactory(
                    assetBase, configuration.getMaxExternalAssetBytes().toBytes());
            builder.useHttpStreamImplementation(assetFactory);
            builder.useProtocolsStreamImplementation(assetFactory, "file");
            for (String configuredFont : configuration.getFontFiles()) {
                if (!StringUtils.hasText(configuredFont)) continue;
                File font = new File(configuredFont.strip());
                if (!font.isFile()) {
                    throw new IllegalStateException("Configured file font does not exist: " + font);
                }
                builder.useFont(font, "FileSans");
            }
            builder.toStream(output);
            builder.run();
            byte[] rendered = output.toByteArray();
            if (rendered.length < 5 || rendered[0] != '%' || rendered[1] != 'P'
                    || rendered[2] != 'D' || rendered[3] != 'F') {
                throw new IllegalStateException("PDF renderer returned an invalid document.");
            }
            return new RenderedFile(rendered, "application/pdf");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not render the PDF file.", failure);
        }
    }

    private URI assetBaseUri() {
        if (!StringUtils.hasText(configuration.getAssetBaseUri())) return null;
        try {
            URI base = URI.create(configuration.getAssetBaseUri().strip()).normalize();
            String scheme = base.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("file")
                    || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Only file, http, and https file asset bases are supported.");
            }
            if (base.getUserInfo() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("File asset-base-uri cannot contain credentials or a fragment.");
            }
            return base;
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Configured file asset-base-uri is invalid.", failure);
        }
    }

    static boolean isAllowedAsset(String rawUri, ExternalResourceType type, URI assetBase) {
        if (!StringUtils.hasText(rawUri) || type != ExternalResourceType.IMAGE_RASTER) return false;
        if (rawUri.regionMatches(true, 0, "data:", 0, "data:".length())) return false;
        if (assetBase == null) return false;
        try {
            URI candidate = URI.create(rawUri).normalize();
            if (candidate.getUserInfo() != null || candidate.getFragment() != null
                    || !sameAuthority(assetBase, candidate)) return false;
            if ("file".equalsIgnoreCase(assetBase.getScheme())) {
                Path root = Path.of(assetBase).toRealPath();
                Path resource = Path.of(candidate).toRealPath();
                return resource.startsWith(root);
            }
            return pathWithin(assetBase.getPath(), candidate.getPath());
        } catch (Exception rejected) {
            return false;
        }
    }

    private static boolean sameAuthority(URI base, URI candidate) {
        if (!Objects.equals(lower(base.getScheme()), lower(candidate.getScheme()))) return false;
        if ("file".equalsIgnoreCase(base.getScheme())) {
            return !StringUtils.hasText(base.getHost()) && !StringUtils.hasText(candidate.getHost());
        }
        return Objects.equals(lower(base.getHost()), lower(candidate.getHost()))
                && effectivePort(base) == effectivePort(candidate);
    }

    private static boolean pathWithin(String basePath, String candidatePath) {
        if (basePath == null || candidatePath == null) return false;
        Path root = Path.of(basePath).normalize();
        Path candidate = Path.of(candidatePath).normalize();
        return candidate.startsWith(root);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String lower(String value) {
        return value != null ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static final class RestrictedAssetFactory implements com.openhtmltopdf.extend.FSStreamFactory {
        private final URI assetBase;
        private final long maximumBytes;
        private long remainingBytes;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        private RestrictedAssetFactory(URI assetBase, long maximumBytes) {
            this.assetBase = assetBase;
            this.maximumBytes = Math.max(1, Math.min(maximumBytes, Integer.MAX_VALUE - 1L));
            this.remainingBytes = this.maximumBytes;
        }

        @Override
        public FSStream getUrl(String url) {
            return new FSStream() {
                private byte[] bytes;

                @Override public InputStream getStream() {
                    return new java.io.ByteArrayInputStream(load());
                }

                @Override public Reader getReader() {
                    return new InputStreamReader(getStream(), StandardCharsets.UTF_8);
                }

                private byte[] load() {
                    if (bytes != null) return bytes;
                    URI uri = URI.create(url).normalize();
                    if (!isAllowedAsset(uri.toString(), ExternalResourceType.IMAGE_RASTER, assetBase)) {
                        bytes = new byte[0];
                        return bytes;
                    }
                    if ("file".equalsIgnoreCase(uri.getScheme())) {
                        try (InputStream input = java.nio.file.Files.newInputStream(Path.of(uri))) {
                            bytes = readLimited(input);
                            return bytes;
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException("Could not load the local PDF asset.", failure);
                        }
                    }
                    if (!("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                        bytes = new byte[0];
                        return bytes;
                    }
                    try {
                        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(20)).GET().build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (response.statusCode() != 200) {
                            response.body().close();
                            bytes = new byte[0];
                            return bytes;
                        }
                        try (InputStream input = response.body()) {
                            bytes = readLimited(input);
                            return bytes;
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Remote PDF asset request was interrupted.", interrupted);
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException("Could not load the remote PDF asset.", failure);
                    }
                }

                private byte[] readLimited(InputStream input) throws java.io.IOException {
                    synchronized (RestrictedAssetFactory.this) {
                        if (remainingBytes <= 0) {
                            throw new IllegalStateException("PDF assets exceed the configured aggregate size limit.");
                        }
                        byte[] loaded = input.readNBytes((int) remainingBytes + 1);
                        if (loaded.length > remainingBytes) {
                            throw new IllegalStateException("PDF assets exceed the configured aggregate size limit.");
                        }
                        remainingBytes -= loaded.length;
                        return loaded;
                    }
                }
            };
        }
    }
}
