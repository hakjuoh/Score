"""Tests for shared lifecycle-state filtering across list services."""

from __future__ import annotations

import pytest

from app.services.core_component_service import CoreComponentService
from app.services.data_type_service import DataTypeService
from app.services.utils.state import normalize_state_filter
from app.utils.state import split_state_filter


class _RecordingRepository:
    def __init__(self) -> None:
        self.list_kwargs: dict[str, object] | None = None

    async def list(self, **kwargs):
        self.list_kwargs = kwargs
        return 0, []


class _ReleaseService:
    async def get(self, _release_id):
        return object()

    async def get_dependent_releases(self, _release_id):
        return []


def test_state_filter_parsing_and_normalization():
    """Whitespace, empty entries, and duplicates are normalized consistently."""
    assert split_state_filter(" WIP, Draft ,,WIP ") == ["WIP", "Draft", "WIP"]
    assert normalize_state_filter([" WIP", "Draft", "WIP "]) == ["WIP", "Draft"]
    assert split_state_filter(None) is None
    assert normalize_state_filter([]) is None


def test_state_filter_rejects_unknown_values():
    """Unknown lifecycle states fail with a useful validation message."""
    with pytest.raises(ValueError, match="Invalid lifecycle states: Review"):
        normalize_state_filter(["WIP", "Review"])


@pytest.mark.asyncio
async def test_core_component_service_normalizes_states_before_repository_query():
    """Core-component list queries pass normalized states to persistence."""
    repository = _RecordingRepository()
    service = CoreComponentService(
        core_component_repository=repository,
        release_service=_ReleaseService(),
        data_type_service=object(),
        account_service_repo=None,
        requester=object(),
    )

    result = await service.list(
        release_id=2,
        types=["ACC", "ASCCP", "BCCP"],
        states=["WIP", " WIP ", "Draft"],
        limit=1,
        offset=0,
    )

    assert result.total == 0
    assert repository.list_kwargs is not None
    assert repository.list_kwargs["states"] == ["WIP", "Draft"]


@pytest.mark.asyncio
async def test_data_type_service_normalizes_states_before_repository_query():
    """Data-type list queries pass normalized states to persistence."""
    repository = _RecordingRepository()
    service = DataTypeService(
        data_type_repository=repository,
        release_service=_ReleaseService(),
        account_service_repo=None,
        requester=object(),
    )

    result = await service.list(
        release_id=2,
        states=["WIP", " WIP ", "Draft"],
        limit=1,
        offset=0,
    )

    assert result.total == 0
    assert repository.list_kwargs is not None
    assert repository.list_kwargs["states"] == ["WIP", "Draft"]
