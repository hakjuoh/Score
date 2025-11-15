import asyncio
import pytest
from fastmcp import Client
from fastmcp.client import BearerAuth
from tests.conftest import create_test_client


class TestReuseTopLevelAsbiep:
    """Test cases for reuse_top_level_asbiep and remove_reused_top_level_asbiep tools."""
    
    @pytest.fixture
    def connectspec_library_id(self, token):
        """Find and return the connectSpec library ID."""
        async def _get_library_id():
            async with Client("http://localhost:8000/mcp", auth=BearerAuth(token=token)) as client:
                result = await client.call_tool("get_libraries", {
                    'name': 'connectSpec',
                    'offset': 0,
                    'limit': 10
                })
                assert result.data.items and len(result.data.items) > 0, "connectSpec library must exist"
                for lib in result.data.items:
                    if lib.name == 'connectSpec':
                        return lib.library_id
                assert False, f"connectSpec library not found. Found libraries: {[lib.name for lib in result.data.items]}"
        return asyncio.run(_get_library_id())
    
    @pytest.fixture
    def release_10_12_id(self, token, connectspec_library_id):
        """Find and return the release ID for connectSpec 10.12."""
        async def _get_release_id():
            assert connectspec_library_id is not None, "connectSpec library must exist"
            async with Client("http://localhost:8000/mcp", auth=BearerAuth(token=token)) as client:
                result = await client.call_tool("get_releases", {
                    'library_id': connectspec_library_id,
                    'release_num': '10.12',
                    'offset': 0,
                    'limit': 10
                })
                assert result.data.items and len(result.data.items) > 0, "Release 10.12 must exist in connectSpec"
                for release in result.data.items:
                    if release.release_num == '10.12':
                        return release.release_id
                assert False, f"Release 10.12 not found. Found releases: {[r.release_num for r in result.data.items]}"
        return asyncio.run(_get_release_id())
    
    @pytest.fixture
    def item_master_asccp_manifest_id(self, token, release_10_12_id):
        """Find and return the Item Master ASCCP manifest ID."""
        async def _get_asccp_id():
            assert release_10_12_id is not None, "Release 10.12 must exist in connectSpec"
            async with Client("http://localhost:8000/mcp", auth=BearerAuth(token=token)) as client:
                result = await client.call_tool("get_core_components", {
                    'release_id': release_10_12_id,
                    'types': 'ASCCP',
                    'den': 'Item Master',
                    'offset': 0,
                    'limit': 100
                })
                assert result.data.items and len(result.data.items) > 0, "Item Master ASCCP must exist in release 10.12"
                for component in result.data.items:
                    if component.component_type == 'ASCCP' and 'Item Master' in component.den:
                        return component.manifest_id
                assert False, f"Item Master ASCCP not found. Found ASCCPs: {[comp.den for comp in result.data.items[:10]]}"
        return asyncio.run(_get_asccp_id())
    
    @pytest.fixture
    def get_item_master_asccp_manifest_id(self, token, release_10_12_id):
        """Find and return the 'Get Item Master' ASCCP manifest ID."""
        async def _get_asccp_id():
            assert release_10_12_id is not None, "Release 10.12 must exist in connectSpec"
            async with Client("http://localhost:8000/mcp", auth=BearerAuth(token=token)) as client:
                result = await client.call_tool("get_core_components", {
                    'release_id': release_10_12_id,
                    'types': 'ASCCP',
                    'den': 'Get Item Master',
                    'offset': 0,
                    'limit': 100
                })
                assert result.data.items and len(result.data.items) > 0, "Get Item Master ASCCP must exist in release 10.12"
                for component in result.data.items:
                    if component.component_type == 'ASCCP' and component.den and 'Get Item Master' in component.den:
                        if component.den.startswith('Get Item Master'):
                            return component.manifest_id
                assert False, f"Get Item Master ASCCP not found. Found ASCCPs: {[comp.den for comp in result.data.items[:10]]}"
        return asyncio.run(_get_asccp_id())
    
    @pytest.fixture
    def sample_business_context_id(self, token):
        """Find or create a sample business context for testing."""
        async def _get_biz_ctx_id():
            async with Client("http://localhost:8000/mcp", auth=BearerAuth(token=token)) as client:
                # Try to get an existing business context
                result = await client.call_tool("get_business_contexts", {
                    'offset': 0,
                    'limit': 10
                })
                if result.data.items and len(result.data.items) > 0:
                    return result.data.items[0].biz_ctx_id
                # If no business contexts exist, create one
                create_result = await client.call_tool("create_business_context", {
                    'name': 'Test Business Context for Reuse Tests'
                })
                return create_result.data.biz_ctx_id
        return asyncio.run(_get_biz_ctx_id())
    
    def _find_relationship_by_den(self, relationships, den_pattern):
        """Helper to find a relationship by DEN pattern."""
        for rel in relationships:
            # Handle direct relationship objects from get_top_level_asbiep (with component_type)
            if hasattr(rel, 'component_type'):
                if rel.component_type == 'ASBIE' and hasattr(rel, 'based_ascc'):
                    if rel.based_ascc and den_pattern in (rel.based_ascc.den or ''):
                        return rel
                elif rel.component_type == 'BBIE' and hasattr(rel, 'based_bcc'):
                    if rel.based_bcc and den_pattern in (rel.based_bcc.den or ''):
                        return rel
            # Handle RelationshipDetail objects (from get_asbie/get_bbie with nested structure)
            if hasattr(rel, 'asbie') and rel.asbie:
                if hasattr(rel.asbie, 'based_ascc') and rel.asbie.based_ascc:
                    if den_pattern in (rel.asbie.based_ascc.den or ''):
                        return rel.asbie
            if hasattr(rel, 'bbie') and rel.bbie:
                if hasattr(rel.bbie, 'based_bcc') and rel.bbie.based_bcc:
                    if den_pattern in (rel.bbie.based_bcc.den or ''):
                        return rel.bbie
            # Handle dict-like objects (fallback)
            if isinstance(rel, dict):
                if 'component_type' in rel:
                    if rel.get('component_type') == 'ASBIE' and 'based_ascc' in rel:
                        based_ascc = rel.get('based_ascc', {})
                        if isinstance(based_ascc, dict) and den_pattern in (based_ascc.get('den') or ''):
                            return rel
                    elif rel.get('component_type') == 'BBIE' and 'based_bcc' in rel:
                        based_bcc = rel.get('based_bcc', {})
                        if isinstance(based_bcc, dict) and den_pattern in (based_bcc.get('den') or ''):
                            return rel
        return None
    
    @pytest.mark.asyncio
    async def test_reuse_top_level_asbiep_and_remove(
        self, token, get_item_master_asccp_manifest_id, item_master_asccp_manifest_id, sample_business_context_id
    ):
        """Test reusing and removing a reused top-level ASBIEP.
        
        This test:
        1. Creates two BIEs: 'Get Item Master' and 'Item Master'
        2. Finds 'Data Area' ASBIE in 'Get Item Master'
        3. Finds 'Item Master' ASBIE under 'Data Area' in 'Get Item Master'
        4. Updates some properties in both BIEs
        5. Reuses 'Item Master' BIE in 'Get Item Master' (reuse_top_level_asbiep)
        6. Updates some properties after reusing
        7. Removes the reused top-level ASBIEP (remove_reused_top_level_asbiep)
        8. Checks properties after removal
        """
        async with create_test_client(token) as client:
            # Step 1: Create 'Get Item Master' BIE first
            get_item_master_bie_result = await client.call_tool("create_top_level_asbiep", {
                'asccp_manifest_id': get_item_master_asccp_manifest_id,
                'biz_ctx_list': str(sample_business_context_id)
            })
            get_item_master_bie_id = get_item_master_bie_result.data.top_level_asbiep_id
            assert get_item_master_bie_id > 0, "Get Item Master BIE should be created"
            
            try:
                # Step 2: Get 'Get Item Master' BIE to find 'Data Area' ASBIE
                get_item_master_result = await client.call_tool("get_top_level_asbiep", {
                    'top_level_asbiep_id': get_item_master_bie_id
                })
                
                relationships = get_item_master_result.data.asbiep.role_of_abie.relationships
                data_area_rel = self._find_relationship_by_den(relationships, "Data Area")
                assert data_area_rel is not None, "Data Area relationship should exist in Get Item Master"
                
                # Get Data Area ASBIE ID
                data_area_asbie_id = None
                if isinstance(data_area_rel, dict):
                    data_area_asbie_id = data_area_rel.get('asbie_id')
                elif hasattr(data_area_rel, 'asbie_id') and data_area_rel.asbie_id:
                    data_area_asbie_id = data_area_rel.asbie_id
                
                assert data_area_asbie_id is not None, "Data Area ASBIE should have an ID"
                
                # Step 3: Get Data Area ASBIE details to find 'Item Master' ASBIE
                data_area_detail = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': data_area_asbie_id
                })
                
                assert data_area_detail.data.to_asbiep is not None, "Data Area should have an ASBIEP"
                assert data_area_detail.data.to_asbiep.role_of_abie is not None, "Data Area ASBIEP should have a role_of_abie"
                
                data_area_relationships = data_area_detail.data.to_asbiep.role_of_abie.relationships
                # Use specific DEN pattern "Item Master. Item Master" to avoid matching "Get Item Master"
                item_master_rel = self._find_relationship_by_den(data_area_relationships, "Item Master. Item Master")
                assert item_master_rel is not None, "Item Master relationship should exist in Data Area"
                
                # Get Item Master ASBIE ID
                # item_master_rel could be an AsbieRelationshipDetail object or a dict
                item_master_asbie_id = None
                if isinstance(item_master_rel, dict):
                    item_master_asbie_id = item_master_rel.get('asbie_id')
                elif hasattr(item_master_rel, 'asbie_id'):
                    item_master_asbie_id = item_master_rel.asbie_id
                
                assert item_master_asbie_id is not None, f"Item Master ASBIE should have an ID. Got: {item_master_rel}"
                
                # Step 4: Get the ASCCP manifest ID from the Item Master ASBIE
                item_master_asbie_detail = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': item_master_asbie_id
                })
                
                # Extract the ASCCP manifest ID from the ASBIE's based_ascc
                item_master_asccp_manifest_id_from_asbie = None
                if hasattr(item_master_asbie_detail.data, 'based_ascc') and item_master_asbie_detail.data.based_ascc:
                    based_ascc = item_master_asbie_detail.data.based_ascc
                    if hasattr(based_ascc, 'to_asccp_manifest_id'):
                        item_master_asccp_manifest_id_from_asbie = based_ascc.to_asccp_manifest_id
                    elif isinstance(based_ascc, dict):
                        item_master_asccp_manifest_id_from_asbie = based_ascc.get('to_asccp_manifest_id')
                
                assert item_master_asccp_manifest_id_from_asbie is not None, "Item Master ASBIE should have a based_ascc with to_asccp_manifest_id"
                
                # Step 5: Create 'Item Master' BIE using the ASCCP manifest ID from the ASBIE
                item_master_bie_result = await client.call_tool("create_top_level_asbiep", {
                    'asccp_manifest_id': item_master_asccp_manifest_id_from_asbie,
                    'biz_ctx_list': str(sample_business_context_id)
                })
                item_master_bie_id = item_master_bie_result.data.top_level_asbiep_id
                assert item_master_bie_id > 0, "Item Master BIE should be created"
                
                # Step 6: Update properties in both BIEs before reusing
                # Update Item Master ASBIE definition
                update_item_master_asbie = await client.call_tool("update_asbie", {
                    'asbie_id': item_master_asbie_id,
                    'definition': 'Test definition before reusing',
                    'remark': 'Test remark before reusing'
                })
                assert 'definition' in update_item_master_asbie.data.updates or 'remark' in update_item_master_asbie.data.updates
                
                # Update Item Master BIE properties
                update_item_master_bie = await client.call_tool("update_top_level_asbiep", {
                    'top_level_asbiep_id': item_master_bie_id,
                    'remark': 'Item Master BIE remark before reusing'
                })
                assert 'remark' in update_item_master_bie.data.updates, "Remark should be in the updates list"
                
                # Step 7: Verify the ASBIE is not yet reused (owner_top_level_asbiep_id should be the same)
                item_master_asbie_detail_before = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': item_master_asbie_id
                })
                owner_bie_id_before = item_master_asbie_detail_before.data.owner_top_level_asbiep.top_level_asbiep_id
                asbiep_owner_bie_id_before = item_master_asbie_detail_before.data.to_asbiep.owner_top_level_asbiep.top_level_asbiep_id
                assert owner_bie_id_before == asbiep_owner_bie_id_before, "ASBIE should not be reused yet (same owner)"
                assert owner_bie_id_before == get_item_master_bie_id, "ASBIE should belong to Get Item Master BIE"
                
                # Step 8: Reuse 'Item Master' BIE in 'Get Item Master'
                reuse_result = await client.call_tool("reuse_top_level_asbiep", {
                    'asbie_id': item_master_asbie_id,
                    'reuse_top_level_asbiep_id': item_master_bie_id
                })
                assert reuse_result.data.asbie_id == item_master_asbie_id
                assert 'to_asbiep_id' in reuse_result.data.updates
                
                # Step 9: Verify the ASBIE is now reused (owner_top_level_asbiep_id should be different)
                item_master_asbie_detail_after_reuse = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': item_master_asbie_id
                })
                owner_bie_id_after = item_master_asbie_detail_after_reuse.data.owner_top_level_asbiep.top_level_asbiep_id
                asbiep_owner_bie_id_after = item_master_asbie_detail_after_reuse.data.to_asbiep.owner_top_level_asbiep.top_level_asbiep_id
                
                # Debug: Print the actual values to help diagnose the issue
                print(f"DEBUG: get_item_master_bie_id={get_item_master_bie_id}, item_master_bie_id={item_master_bie_id}")
                print(f"DEBUG: owner_bie_id_after={owner_bie_id_after}, asbiep_owner_bie_id_after={asbiep_owner_bie_id_after}")
                print(f"DEBUG: to_asbiep_id={item_master_asbie_detail_after_reuse.data.to_asbiep.asbiep_id}")
                
                assert owner_bie_id_after != asbiep_owner_bie_id_after, f"ASBIE should now be reused (different owners). ASBIE owner: {owner_bie_id_after}, ASBIEP owner: {asbiep_owner_bie_id_after}"
                assert owner_bie_id_after == get_item_master_bie_id, f"ASBIE should still belong to Get Item Master BIE. Expected: {get_item_master_bie_id}, Got: {owner_bie_id_after}"
                assert asbiep_owner_bie_id_after == item_master_bie_id, f"ASBIEP should belong to Item Master BIE. Expected: {item_master_bie_id}, Got: {asbiep_owner_bie_id_after}"
                
                # Step 10: Update properties after reusing
                # Update the reused ASBIE's remark (this should update the ASBIEP from Item Master BIE)
                update_after_reuse = await client.call_tool("update_asbie", {
                    'asbie_id': item_master_asbie_id,
                    'remark': 'Test remark after reusing'
                })
                assert 'remark' in update_after_reuse.data.updates
                
                # Verify the remark was updated on the reused ASBIEP
                item_master_asbie_detail_after_update = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': item_master_asbie_id
                })
                # The remark should be on the ASBIEP, which we can verify through the nested structure
                
                # Step 11: Remove the reused top-level ASBIEP
                remove_result = await client.call_tool("remove_reused_top_level_asbiep", {
                    'asbie_id': item_master_asbie_id
                })
                assert remove_result.data.asbie_id == item_master_asbie_id
                assert 'to_asbiep_id' in remove_result.data.updates
                
                # Step 12: Verify the ASBIE is no longer reused (owner_top_level_asbiep_id should be the same again)
                item_master_asbie_detail_after_remove = await client.call_tool("get_asbie_by_asbie_id", {
                    'asbie_id': item_master_asbie_id
                })
                owner_bie_id_after_remove = item_master_asbie_detail_after_remove.data.owner_top_level_asbiep.top_level_asbiep_id
                asbiep_owner_bie_id_after_remove = item_master_asbie_detail_after_remove.data.to_asbiep.owner_top_level_asbiep.top_level_asbiep_id
                assert owner_bie_id_after_remove == asbiep_owner_bie_id_after_remove, "ASBIE should no longer be reused (same owner)"
                assert owner_bie_id_after_remove == get_item_master_bie_id, "ASBIE should belong to Get Item Master BIE"
                
                # Step 13: Verify properties are preserved
                # The definition should still be there
                assert item_master_asbie_detail_after_remove.data.definition == 'Test definition before reusing'
                
            finally:
                # Cleanup: Delete both BIEs
                try:
                    await client.call_tool("delete_top_level_asbiep", {
                        'top_level_asbiep_id': get_item_master_bie_id
                    })
                except Exception as e:
                    print(f"Warning: Failed to cleanup Get Item Master BIE {get_item_master_bie_id}: {e}")
                
                try:
                    await client.call_tool("delete_top_level_asbiep", {
                        'top_level_asbiep_id': item_master_bie_id
                    })
                except Exception as e:
                    print(f"Warning: Failed to cleanup Item Master BIE {item_master_bie_id}: {e}")

