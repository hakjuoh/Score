"""Tests for MariaDB data-type lifecycle-state filtering."""

from sqlalchemy.dialects import mysql

from app.repositories.vendors.mariadb.data_type_repository import _build_where_clauses


def test_build_where_clauses_applies_exact_match_states():
    """The data-type SQL builder adds an exact lifecycle-state predicate."""
    clauses = _build_where_clauses(
        release_id=2,
        dependent_release_ids=[],
        states=["WIP", "Draft"],
        den=None,
        representation_term=None,
        creation_timestamp_before=None,
        creation_timestamp_after=None,
        last_update_timestamp_before=None,
        last_update_timestamp_after=None,
    )

    compiled = " AND ".join(
        str(clause.compile(dialect=mysql.dialect(), compile_kwargs={"literal_binds": True})) for clause in clauses
    )

    assert "dt.state IN ('WIP', 'Draft')" in compiled


def test_build_where_clauses_omits_state_predicate_without_filter():
    """The data-type SQL builder remains unfiltered when states are omitted."""
    clauses = _build_where_clauses(
        release_id=2,
        dependent_release_ids=[],
        states=None,
        den=None,
        representation_term=None,
        creation_timestamp_before=None,
        creation_timestamp_after=None,
        last_update_timestamp_before=None,
        last_update_timestamp_after=None,
    )

    compiled = " AND ".join(
        str(clause.compile(dialect=mysql.dialect(), compile_kwargs={"literal_binds": True})) for clause in clauses
    )

    assert "dt.state IN" not in compiled
