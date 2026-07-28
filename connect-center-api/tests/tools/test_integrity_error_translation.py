"""Database integrity errors reach tool callers as actionable sentences.

Deleting a record other records still point at is an expected outcome, not an
internal failure. These tests pin the translation from the constraint text the
database reports to a message that names the records standing in the way, so a
referential-integrity conflict is never reported as an unexplained failure.
"""

from __future__ import annotations

from fastapi import HTTPException
from fastmcp.exceptions import ToolError
from sqlalchemy.exc import IntegrityError

from app.tools import _to_tool_error, integrity_error_cause

FALLBACK = "Unable to delete context category 97."


def _integrity_error(message: str) -> IntegrityError:
    """Build an IntegrityError carrying a driver message."""
    return IntegrityError("DELETE FROM ctx_category", {}, Exception(message))


def test_parent_row_conflict_names_the_referencing_records() -> None:
    error = _integrity_error(
        "(1451, \"Cannot delete or update a parent row: a foreign key constraint "
        "fails (`oagi`.`ctx_scheme`, CONSTRAINT `ctx_scheme_ctx_category_id_fk` "
        "FOREIGN KEY (`ctx_category_id`) REFERENCES `ctx_category` "
        "(`ctx_category_id`))\")"
    )

    tool_error = _to_tool_error(error, fallback=FALLBACK)

    assert isinstance(tool_error, ToolError)
    assert str(tool_error) == (
        f"{FALLBACK} It is still referenced by context scheme records. "
        "Delete or reassign those records first."
    )


def test_parent_row_conflict_without_a_schema_qualifier() -> None:
    error = _integrity_error(
        "Cannot delete or update a parent row: a foreign key constraint fails "
        "(`biz_ctx_value`, CONSTRAINT `biz_ctx_value_fk` FOREIGN KEY "
        "(`ctx_scheme_value_id`) REFERENCES `ctx_scheme_value` "
        "(`ctx_scheme_value_id`))"
    )

    assert integrity_error_cause(error) == (
        "It is still referenced by business context value records. "
        "Delete or reassign those records first."
    )


def test_unmapped_table_keeps_its_own_name() -> None:
    error = _integrity_error(
        "Cannot delete or update a parent row: a foreign key constraint fails "
        "(`oagi`.`oas_message_body`, CONSTRAINT `oas_fk` FOREIGN KEY (`x`) "
        "REFERENCES `top_level_asbiep` (`top_level_asbiep_id`))"
    )

    assert integrity_error_cause(error) == (
        "It is still referenced by oas_message_body records. "
        "Delete or reassign those records first."
    )


def test_child_row_conflict_names_the_missing_parent() -> None:
    error = _integrity_error(
        "(1452, \"Cannot add or update a child row: a foreign key constraint "
        "fails (`oagi`.`ctx_scheme`, CONSTRAINT `ctx_scheme_ctx_category_id_fk` "
        "FOREIGN KEY (`ctx_category_id`) REFERENCES `ctx_category` "
        "(`ctx_category_id`))\")"
    )

    assert integrity_error_cause(error) == (
        "It refers to a context category record that does not exist."
    )


def test_duplicate_entry_is_reported_as_a_uniqueness_conflict() -> None:
    error = _integrity_error(
        "(1062, \"Duplicate entry 'Country' for key 'ctx_category.uk_name'\")"
    )

    assert integrity_error_cause(error) == (
        "Another record with the same unique value already exists."
    )


def test_unrecognized_integrity_text_stays_with_the_caller_fallback() -> None:
    error = _integrity_error("(1364, \"Field 'guid' doesn't have a default value\")")

    assert integrity_error_cause(error) is None
    assert str(_to_tool_error(error, fallback=FALLBACK)) == FALLBACK


def test_other_exception_translations_are_unchanged() -> None:
    assert str(_to_tool_error(ValueError("bad input"), fallback=FALLBACK)) == "bad input"
    assert str(_to_tool_error(LookupError("not found"), fallback=FALLBACK)) == "not found"
    assert str(
        _to_tool_error(HTTPException(status_code=409, detail="in use"), fallback=FALLBACK)
    ) == "in use"
    assert str(_to_tool_error(ToolError("explained"), fallback=FALLBACK)) == "explained"
    assert str(_to_tool_error(RuntimeError("boom"), fallback=FALLBACK)) == FALLBACK
