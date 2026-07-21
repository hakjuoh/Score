package org.oagi.score.gateway.http.configuration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreToolIndexTest {

    @Test
    void prioritizesRequestedEntitiesAcrossALongWorkflowQuery() {
        ScoreToolIndex index = index(
                tool("get_top_level_asbiep", "Get a top-level ASBIEP by ID"),
                tool("create_asbie", "Create an ASBIE"),
                tool("get_bbie_by_based_bcc_manifest_id", "Get a BBIE by BCC manifest ID"),
                tool("get_agency_id_lists", "Get paginated agency ID lists"),
                tool("get_business_contexts", "Get a paginated list of business contexts"),
                tool("get_business_context", "Get a specific business context by ID"),
                tool("get_context_schemes", "Get a paginated list of context schemes"),
                tool("get_context_scheme", "Get a specific context scheme by ID"),
                tool("create_business_context_value",
                        "Create a new value for a specific business context, linking it to a context scheme value"),
                tool("update_business_context_value",
                        "Update the linked context scheme value for an existing business context value"));

        List<String> results = search(index, "connectCenter business context ID 78 get details, "
                + "list or search available context schemes and context values, add or assign "
                + "context values to a business context, then read back and verify updated "
                + "business context relationships");

        assertThat(results)
                .contains("get_business_context", "get_business_contexts",
                        "get_context_schemes", "get_context_scheme",
                        "create_business_context_value", "update_business_context_value")
                .doesNotContain("get_top_level_asbiep", "create_asbie",
                        "get_bbie_by_based_bcc_manifest_id", "get_agency_id_lists");
    }

    @Test
    void actionWordsDoNotOutrankTheRequestedEntity() {
        ScoreToolIndex index = index(
                tool("get_top_level_asbiep", "Get a top-level ASBIEP by ID"),
                tool("get_context_schemes", "Get a paginated list of context schemes"),
                tool("create_context_scheme_value", "Create a value for a context scheme"),
                tool("delete_context_scheme", "Delete a context scheme"));

        List<String> results = search(index,
                "list context schemes and context scheme values so I can add one");

        assertThat(results)
                .contains("get_context_schemes", "create_context_scheme_value")
                .doesNotContain("get_top_level_asbiep");
    }

    @Test
    void keepsSessionsIsolatedAndReplacesABatchIndex() {
        ScoreToolIndex index = index(tool("get_business_context", "Get a business context"));
        index.indexTools("other", List.of(tool("get_releases", "Get releases")));
        index.indexTools("conversation", List.of(tool("get_context_schemes", "Get context schemes")));

        assertThat(search(index, "business context")).doesNotContain("get_business_context");
        assertThat(search(index, "context schemes")).containsExactly("get_context_schemes");
        assertThat(search(index, "releases", "other")).containsExactly("get_releases");
    }

    @Test
    void letsTheModelSelectExactDeferredToolsWithoutRegexParsing() {
        ScoreToolIndex index = index(
                tool("create_context_category", "Create a context category"),
                tool("update_context_scheme", "Update a context scheme"),
                tool("delete_business_context", "Delete a business context"));

        assertThat(search(index,
                "select:update_context_scheme,delete_business_context,unknown_tool"))
                .containsExactly("update_context_scheme", "delete_business_context");
        assertThat(search(index, "CREATE_CONTEXT_CATEGORY"))
                .containsExactly("create_context_category");
    }

    private ScoreToolIndex index(ToolReference... tools) {
        ScoreToolIndex index = new ScoreToolIndex();
        index.indexTools("conversation", List.of(tools));
        return index;
    }

    private List<String> search(ScoreToolIndex index, String query) {
        return search(index, query, "conversation");
    }

    private List<String> search(ScoreToolIndex index, String query, String session) {
        return index.search(new ToolSearchRequest(session, query, 10, null))
                .toolReferences().stream().map(ToolReference::toolName).toList();
    }

    private ToolReference tool(String name, String summary) {
        return ToolReference.builder().toolName(name).summary(summary).build();
    }
}
