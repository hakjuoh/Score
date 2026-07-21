"""Regression tests for MariaDB context-scheme pagination."""

from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock

from app.repositories.vendors.mariadb.ctx_scheme_repository import MariaDbCtxSchemeRepository


class _ScalarResult:
    """Minimal SQLAlchemy scalar result stub."""

    def __init__(self, value):
        self._value = value

    def scalar_one(self):
        """Return the configured scalar."""
        return self._value


class _Scalars:
    """Minimal SQLAlchemy scalars result stub."""

    def __init__(self, values):
        self._values = values

    def all(self):
        """Return configured values."""
        return self._values


class _PageResult:
    """Minimal SQLAlchemy page result stub."""

    def __init__(self, values):
        self._values = values

    def scalars(self):
        """Return the scalar projection."""
        return _Scalars(self._values)


class _RowsResult:
    """Minimal SQLAlchemy row result stub."""

    def __init__(self, rows):
        self._rows = rows

    def all(self):
        """Return configured joined rows."""
        return self._rows


async def test_list_pages_schemes_before_loading_their_values():
    """Multiple values owned by one scheme must not consume the page limit."""
    now = datetime.now(UTC).replace(tzinfo=None)

    def row(scheme_pk, scheme_id, value_pk=None):
        return (
            scheme_pk,
            f"scheme-guid-{scheme_pk}",
            scheme_id,
            f"Scheme {scheme_pk}",
            None,
            "agency",
            "1.0",
            now,
            now,
            1,
            1,
            7,
            "Category",
            value_pk,
            f"value-guid-{value_pk}" if value_pk else None,
            f"value-{value_pk}" if value_pk else None,
            None,
        )

    session = SimpleNamespace(
        execute=AsyncMock(
            side_effect=[
                _ScalarResult(2),
                _PageResult([1, 2]),
                _RowsResult([row(1, "A", 11), row(1, "A", 12), row(2, "B")]),
            ]
        )
    )
    repository = MariaDbCtxSchemeRepository(session)

    total, schemes = await repository.list(limit=2, offset=0, sorts=[])

    assert total == 2
    assert [scheme.ctx_scheme_id for scheme in schemes] == [1, 2]
    assert [value.ctx_scheme_value_id for value in schemes[0].values] == [11, 12]
    assert schemes[1].values == []
    assert session.execute.await_count == 3
