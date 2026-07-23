"""Tests for MariaDB core component repository helpers."""

from datetime import datetime

import pytest
from sqlalchemy import literal, select
from sqlalchemy.dialects import mysql

from app.repositories.vendors.mariadb.core_component_repository import MariaDbCoreComponentRepository
from app.repositories.vendors.mariadb.models.core_component import Acc, AccManifest
from app.repositories.vendors.mariadb.models.tag import AccManifestTag


class _EmptyResult:
    def first(self):
        return None


class _FakeSession:
    async def execute(self, _statement):
        return _EmptyResult()


@pytest.mark.asyncio
@pytest.mark.parametrize("component_type", ["ACC", "ASCCP", "BCCP"])
async def test_fetch_one_core_uses_tag_names_keyword(monkeypatch, component_type):
    """Detail fetches use the shared component select with the current tag filter keyword."""
    repo = object.__new__(MariaDbCoreComponentRepository)
    repo._session = _FakeSession()
    seen = {}

    def fake_build_component_select(self, **kwargs):
        seen.update(kwargs)
        return select(literal(1).label("manifest_id"))

    monkeypatch.setattr(
        MariaDbCoreComponentRepository,
        "_build_component_select",
        fake_build_component_select,
    )

    result = await repo._fetch_one_core(component_type, 123)

    assert result is None
    assert seen["tag_names"] is None
    assert seen["states"] is None
    assert "tag" not in seen


class _ScalarResult:
    def scalar_one(self):
        return 0


class _RowsResult:
    def all(self):
        return []


class _ListSession:
    def __init__(self):
        self.execute_count = 0

    async def execute(self, _statement):
        self.execute_count += 1
        return _ScalarResult() if self.execute_count == 1 else _RowsResult()


@pytest.mark.asyncio
async def test_list_applies_states_to_each_component_select(monkeypatch):
    """Unified list queries pass states to the ACC, ASCCP, and BCCP branches."""
    repo = object.__new__(MariaDbCoreComponentRepository)
    repo._session = _ListSession()
    seen_states = []

    def fake_build_component_select(self, **kwargs):
        seen_states.append(kwargs["states"])
        return select(
            literal(kwargs["component_type"]).label("component_type"),
            literal("Example. Details").label("den"),
            literal("Example").label("name"),
            literal("Definition").label("definition"),
            literal(datetime(2026, 1, 1)).label("creation_timestamp"),
            literal(datetime(2026, 1, 1)).label("last_update_timestamp"),
        )

    monkeypatch.setattr(
        MariaDbCoreComponentRepository,
        "_build_component_select",
        fake_build_component_select,
    )

    total, rows = await repo.list(
        release_id=2,
        dependent_release_ids=[],
        types=["ACC", "ASCCP", "BCCP"],
        states=["WIP", "Draft"],
        limit=1,
        offset=0,
        sorts=[],
    )

    assert total == 0
    assert rows == []
    assert seen_states == [["WIP", "Draft"]] * 3


def _compile_acc_select(states: list[str] | None) -> str:
    repo = object.__new__(MariaDbCoreComponentRepository)
    statement = repo._build_component_select(
        component_type="ACC",
        manifest_model=AccManifest,
        component_model=Acc,
        manifest_id_col=AccManifest.acc_manifest_id,
        manifest_release_col=AccManifest.release_id,
        manifest_log_col=AccManifest.log_id,
        manifest_component_id_col=AccManifest.acc_id,
        component_id_col=Acc.acc_id,
        component_namespace_col=Acc.namespace_id,
        component_owner_col=Acc.owner_user_id,
        component_created_by_col=Acc.created_by,
        component_updated_by_col=Acc.last_updated_by,
        guid_col=Acc.guid,
        den_col=AccManifest.den,
        name_col=Acc.object_class_term,
        definition_col=Acc.definition,
        definition_source_col=Acc.definition_source,
        state_col=Acc.state,
        is_deprecated_col=Acc.is_deprecated,
        creation_ts_col=Acc.creation_timestamp,
        update_ts_col=Acc.last_update_timestamp,
        tag_link_model=AccManifestTag,
        tag_link_manifest_col=AccManifestTag.acc_manifest_id,
        release_ids=[2],
        states=states,
        den=None,
        tag_names=None,
        creation_timestamp_before=None,
        creation_timestamp_after=None,
        last_update_timestamp_before=None,
        last_update_timestamp_after=None,
    )
    return str(statement.compile(dialect=mysql.dialect(), compile_kwargs={"literal_binds": True}))


def test_component_select_applies_exact_match_states():
    """The real core-component SQL applies and omits exact state predicates correctly."""
    assert "acc.state IN ('WIP', 'Draft')" in _compile_acc_select(["WIP", "Draft"])
    assert "acc.state IN" not in _compile_acc_select(None)
