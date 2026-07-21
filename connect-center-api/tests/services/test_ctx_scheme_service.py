"""Regression tests for context-scheme lifecycle rules."""

from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.services.ctx_scheme_service import CtxSchemeService
from app.types.identifiers import CtxSchemeId


async def test_delete_allows_a_scheme_that_has_a_parent_category():
    """Deleting a child scheme must not be blocked by its parent category link."""
    repository = SimpleNamespace(
        get_biz_ctx_ids_using_ctx_scheme_value=AsyncMock(return_value=[]),
        delete=AsyncMock(return_value=True),
    )
    service = CtxSchemeService(repository, requester=object())
    service.get = AsyncMock(
        return_value=SimpleNamespace(
            ctx_category=SimpleNamespace(ctx_category_id=7, name="Parent"),
            values=[],
        )
    )

    deleted = await service.delete(CtxSchemeId(11))

    assert deleted is True
    repository.delete.assert_awaited_once_with(CtxSchemeId(11))
