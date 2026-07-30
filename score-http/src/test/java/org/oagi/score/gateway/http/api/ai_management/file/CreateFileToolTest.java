package org.oagi.score.gateway.http.api.ai_management.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
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
        assertThat(result.json()).contains("file-1", "report.md").doesNotContain("/download");
    }
}
