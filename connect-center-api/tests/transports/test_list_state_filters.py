"""Contract tests for lifecycle-state filters on REST and MCP list endpoints."""

from __future__ import annotations

import pytest
from fastapi import FastAPI, HTTPException
from fastmcp.exceptions import ValidationError
from httpx import ASGITransport, AsyncClient
from jsonschema import ValidationError as JsonSchemaValidationError
from jsonschema import validate as validate_json_schema

from app.deps import get_core_component_service, get_data_type_service
from app.routes.core_component import get_core_component_list
from app.routes.core_component import router as core_component_router
from app.routes.data_type import get_data_type_list
from app.routes.data_type import router as data_type_router
from app.services.models.core_component import CORE_COMPONENT_TYPES
from app.services.utils.pagination import PaginationResponse
from app.services.utils.state import CC_STATES, normalize_state_filter
from app.tools.core_component import get_core_components
from app.tools.core_component import mcp as core_component_mcp
from app.tools.data_type import get_data_types
from app.tools.data_type import mcp as data_type_mcp


class _RecordingListService:
    def __init__(self, *, total: int) -> None:
        self.total = total
        self.list_kwargs: dict[str, object] | None = None

    async def list(self, **kwargs):
        self.list_kwargs = kwargs
        return PaginationResponse(
            items=[],
            total=self.total,
            limit=int(kwargs["limit"]),
            offset=int(kwargs["offset"]),
        )


class _ValidatingListService(_RecordingListService):
    async def list(self, **kwargs):
        normalize_state_filter(kwargs["states"])
        return await super().list(**kwargs)


def _rest_query_schema(path: str, parameter_name: str) -> dict[str, object]:
    app = FastAPI()
    app.include_router(core_component_router)
    app.include_router(data_type_router)
    parameters = app.openapi()["paths"][path]["get"]["parameters"]
    return next(parameter["schema"] for parameter in parameters if parameter["name"] == parameter_name)


def _rest_app(service: _RecordingListService) -> FastAPI:
    app = FastAPI()
    app.include_router(core_component_router)
    app.include_router(data_type_router)
    app.dependency_overrides[get_core_component_service] = lambda: service
    app.dependency_overrides[get_data_type_service] = lambda: service
    return app


@pytest.mark.parametrize("path", ["/core-components", "/data-types"])
def test_fastapi_list_endpoints_publish_comma_separated_states_filter(path):
    """Both REST operations publish a standards-valid comma-separated contract."""
    schema = _rest_query_schema(path, "states")

    validate_json_schema("WIP,Draft", schema)
    with pytest.raises(JsonSchemaValidationError):
        validate_json_schema("WIP,Review", schema)

    assert "enum" not in schema
    assert schema["examples"] == ["WIP", "WIP,Draft"]
    assert schema["x-item-enum"] == list(CC_STATES)
    assert schema["x-comma-separated"] is True


def test_fastapi_core_component_types_use_comma_separated_item_enum():
    """The REST component-type filter uses the same CSV item-enum contract."""
    schema = _rest_query_schema("/core-components", "types")

    validate_json_schema("ACC,ASCCP", schema)
    with pytest.raises(JsonSchemaValidationError):
        validate_json_schema("ACC,DT", schema)

    assert "enum" not in schema
    assert schema["examples"] == ["ACC", "ACC,ASCCP"]
    assert schema["x-item-enum"] == list(CORE_COMPONENT_TYPES)
    assert schema["x-comma-separated"] is True


@pytest.mark.asyncio
async def test_fastapi_http_parses_comma_separated_component_types():
    """Actual FastAPI request parsing forwards a CSV component-type query."""
    service = _RecordingListService(total=0)
    transport = ASGITransport(app=_rest_app(service))

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(
            "/core-components",
            params={"release_id": 2, "types": "ACC,ASCCP", "limit": 1},
        )

    assert response.status_code == 200
    assert service.list_kwargs is not None
    assert service.list_kwargs["types"] == ["ACC", "ASCCP"]


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["/core-components", "/data-types"])
async def test_fastapi_http_parses_comma_separated_states(path):
    """Actual FastAPI request parsing forwards a CSV state query as two values."""
    service = _RecordingListService(total=0)
    transport = ASGITransport(app=_rest_app(service))

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(path, params={"release_id": 2, "states": "WIP,Draft", "limit": 1})

    assert response.status_code == 200
    assert service.list_kwargs is not None
    assert service.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
@pytest.mark.parametrize("path", ["/core-components", "/data-types"])
async def test_fastapi_http_rejects_unknown_states(path):
    """Actual FastAPI requests map unknown lifecycle states to HTTP 400."""
    service = _ValidatingListService(total=0)
    transport = ASGITransport(app=_rest_app(service))

    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get(path, params={"release_id": 2, "states": "Review", "limit": 1})

    assert response.status_code == 400
    assert "Invalid lifecycle states: Review" in response.json()["detail"]["cause"]


@pytest.mark.asyncio
async def test_fastapi_core_component_list_forwards_states():
    """The REST core-component route forwards parsed lifecycle states."""
    service = _RecordingListService(total=68)

    response = await get_core_component_list(
        release_id=2,
        types=None,
        states="WIP,Draft",
        den=None,
        tag=None,
        owner=None,
        updater=None,
        created_on=None,
        last_updated_on=None,
        order_by=None,
        offset=0,
        limit=1,
        core_component_service=service,
    )

    assert response.total_items == 68
    assert service.list_kwargs is not None
    assert service.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
async def test_fastapi_data_type_list_forwards_states():
    """The REST data-type route forwards parsed lifecycle states."""
    service = _RecordingListService(total=2)

    response = await get_data_type_list(
        release_id=2,
        states="WIP,Draft",
        den=None,
        representation_term=None,
        owner=None,
        updater=None,
        created_on=None,
        last_updated_on=None,
        order_by=None,
        offset=0,
        limit=1,
        data_type_service=service,
    )

    assert response.total_items == 2
    assert service.list_kwargs is not None
    assert service.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("server", "tool_name"),
    [(core_component_mcp, "get_core_components"), (data_type_mcp, "get_data_types")],
)
async def test_fastmcp_list_tools_publish_typed_states_filter(server, tool_name):
    """Both MCP list tools publish the same typed lifecycle-state contract."""
    tool = next(tool for tool in await server.list_tools() if tool.name == tool_name)
    states_schema = tool.parameters["properties"]["states"]["anyOf"][0]

    assert states_schema == {
        "items": {"enum": list(CC_STATES), "type": "string"},
        "type": "array",
    }


@pytest.mark.asyncio
async def test_fastmcp_core_component_list_forwards_states():
    """The MCP core-component tool forwards lifecycle states."""
    service = _RecordingListService(total=68)

    response = await get_core_components(
        release_id=2,
        types=None,
        states=["WIP", "Draft"],
        limit=1,
        core_component_service=service,
    )

    assert response.total_items == 68
    assert service.list_kwargs is not None
    assert service.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
async def test_fastmcp_data_type_list_forwards_states():
    """The MCP data-type tool forwards lifecycle states."""
    service = _RecordingListService(total=2)

    response = await get_data_types(
        release_id=2,
        states=["WIP", "Draft"],
        limit=1,
        data_type_service=service,
    )

    assert response.total_items == 2
    assert service.list_kwargs is not None
    assert service.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
async def test_fastapi_list_endpoints_preserve_omitted_states():
    """Omitting the REST state filter preserves the previous unfiltered behavior."""
    core_service = _RecordingListService(total=1)
    await get_core_component_list(
        release_id=2,
        types=None,
        states=None,
        den=None,
        tag=None,
        owner=None,
        updater=None,
        created_on=None,
        last_updated_on=None,
        order_by=None,
        offset=0,
        limit=1,
        core_component_service=core_service,
    )
    data_type_service = _RecordingListService(total=1)
    await get_data_type_list(
        release_id=2,
        states=None,
        den=None,
        representation_term=None,
        owner=None,
        updater=None,
        created_on=None,
        last_updated_on=None,
        order_by=None,
        offset=0,
        limit=1,
        data_type_service=data_type_service,
    )

    assert core_service.list_kwargs is not None
    assert core_service.list_kwargs["states"] is None
    assert data_type_service.list_kwargs is not None
    assert data_type_service.list_kwargs["states"] is None


@pytest.mark.asyncio
@pytest.mark.parametrize("route_name", ["core_components", "data_types"])
async def test_fastapi_list_endpoints_reject_unknown_states(route_name):
    """Both REST list operations map an unknown lifecycle state to HTTP 400."""
    service = _ValidatingListService(total=0)

    with pytest.raises(HTTPException) as caught:
        if route_name == "core_components":
            await get_core_component_list(
                release_id=2,
                types=None,
                states="Review",
                den=None,
                tag=None,
                owner=None,
                updater=None,
                created_on=None,
                last_updated_on=None,
                order_by=None,
                offset=0,
                limit=1,
                core_component_service=service,
            )
        else:
            await get_data_type_list(
                release_id=2,
                states="Review",
                den=None,
                representation_term=None,
                owner=None,
                updater=None,
                created_on=None,
                last_updated_on=None,
                order_by=None,
                offset=0,
                limit=1,
                data_type_service=service,
            )

    assert caught.value.status_code == 400
    assert "Invalid lifecycle states: Review" in caught.value.detail["cause"]


@pytest.mark.asyncio
async def test_fastmcp_list_tools_preserve_omitted_states():
    """Omitting the MCP state filter preserves the previous unfiltered behavior."""
    core_service = _RecordingListService(total=1)
    await get_core_components(
        release_id=2,
        limit=1,
        core_component_service=core_service,
    )
    data_type_service = _RecordingListService(total=1)
    await get_data_types(
        release_id=2,
        limit=1,
        data_type_service=data_type_service,
    )

    assert core_service.list_kwargs is not None
    assert core_service.list_kwargs["states"] is None
    assert data_type_service.list_kwargs is not None
    assert data_type_service.list_kwargs["states"] is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("server", "tool_name"),
    [(core_component_mcp, "get_core_components"), (data_type_mcp, "get_data_types")],
)
async def test_fastmcp_list_tools_reject_unknown_states(server, tool_name):
    """Both MCP list operations reject states outside the shared lifecycle enum."""
    tool = next(tool for tool in await server.list_tools() if tool.name == tool_name)

    with pytest.raises(ValidationError, match="Review"):
        await tool.run({"release_id": 2, "states": ["Review"]})
