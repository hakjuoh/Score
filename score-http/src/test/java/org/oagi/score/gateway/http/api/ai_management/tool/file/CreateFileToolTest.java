package org.oagi.score.gateway.http.api.ai_management.tool.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileDescriptor;
import org.oagi.score.gateway.http.api.ai_management.tool.file.AiFileService;
import org.oagi.score.gateway.http.api.ai_management.tool.file.CreateFileTool;
import org.oagi.score.gateway.http.common.model.ScoreUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateFileToolTest {

    @Test
    void createsFileThroughLocalPlatformContractWithoutExposingStorageLocation() {
        ScoreUser requester = mock(ScoreUser.class);
        AiFileService service = mock(AiFileService.class);
        ExecutionScope scope = new ExecutionScope("request-1", "conversation-1", "user-1", 0,
                ExecutionScope.Purpose.USER_RESPONSE, List.of());
        when(service.create(eq(requester), eq(scope), eq("markdown"), eq("report.md"),
                any(), any())).thenReturn(new AiFileDescriptor("file-1", "markdown",
                "report.md", "text/markdown", 12, "abc", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), "/download"));
        CreateFileTool tool = new CreateFileTool(requester, service, new ObjectMapper());

        AiTool.ToolResult result = tool.execute(new AiTool.ToolArguments("""
                {"format":"markdown","filename":"report.md","content":"# Report"}
                """), new AiTool.ToolExecutionContext(scope, java.util.Map.of()));

        assertThat(tool.specification().effect()).isEqualTo(AiTool.ToolEffect.OUTPUT_WRITE);
        assertThat(tool.specification().outputSchema())
                .contains("\"fileId\"", "\"format\"", "\"filename\"", "\"mediaType\"",
                        "\"size\"", "\"sha256\"");
        assertThat(result.json()).contains("file-1", "report.md").doesNotContain("/download");
    }

    @Test
    void generatesInputAndOutputSchemasFromTheirRecordProperties() throws Exception {
        CreateFileTool tool = new CreateFileTool(mock(ScoreUser.class),
                mock(AiFileService.class), new ObjectMapper());

        var inputSchema = new ObjectMapper().readTree(tool.specification().inputSchema());
        assertThat(inputSchema.path("properties").propertyStream()
                .map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder(
                        "format", "filename", "content", "options");
        assertThat(inputSchema.path("required").valueStream()
                .map(JsonNode::asText)).containsExactlyInAnyOrder("format", "content");
        assertThat(inputSchema.path("additionalProperties").asBoolean()).isFalse();

        var outputSchema = new ObjectMapper().readTree(tool.specification().outputSchema());
        assertThat(outputSchema.path("required").valueStream()
                .map(JsonNode::asText)).containsExactlyInAnyOrder(
                        "fileId", "format", "filename", "mediaType", "size", "sha256");
        assertThat(outputSchema.at("/properties/size/type").asText()).isEqualTo("integer");
        assertThat(outputSchema.at("/properties/size/minimum").asLong()).isZero();
        assertThat(outputSchema.at("/properties/sha256/pattern").asText())
                .isEqualTo("^[a-fA-F0-9]{64}$");
        assertThat(outputSchema.path("additionalProperties").asBoolean()).isFalse();
    }
}
