"""Reusable OpenAPI metadata helpers for REST query parameters."""

from __future__ import annotations

from collections.abc import Sequence
from re import escape


def comma_separated_enum_schema(values: Sequence[str], *, examples: Sequence[str]) -> dict[str, object]:
    """Describe a CSV query whose individual values come from a fixed set."""
    if not values:
        raise ValueError("Comma-separated enum values must not be empty.")

    item_pattern = "|".join(escape(value) for value in values)
    return {
        "pattern": rf"^\s*(?:{item_pattern})\s*(?:,\s*(?:{item_pattern})\s*)*$",
        "examples": list(examples),
        "x-item-enum": list(values),
        "x-comma-separated": True,
    }
