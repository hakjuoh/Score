"""Shared lifecycle-state types and list-filter normalization."""

from __future__ import annotations

from typing import Literal, cast, get_args

CcState = Literal[
    "Deleted",
    "WIP",
    "Draft",
    "QA",
    "Candidate",
    "Production",
    "ReleaseDraft",
    "Published",
]

CC_STATES = cast(tuple[CcState, ...], get_args(CcState))
_CC_STATE_SET = frozenset(CC_STATES)


def normalize_state_filter(states: list[str] | None) -> list[CcState] | None:
    """Validate, trim, and de-duplicate exact-match lifecycle states."""
    if not states:
        return None

    normalized: list[CcState] = []
    invalid: list[str] = []
    for raw_state in states:
        state = raw_state.strip()
        if not state:
            continue
        if state not in _CC_STATE_SET:
            if state not in invalid:
                invalid.append(state)
            continue
        typed_state = cast(CcState, state)
        if typed_state not in normalized:
            normalized.append(typed_state)

    if invalid:
        raise ValueError(f"Invalid lifecycle states: {', '.join(invalid)}. Allowed values are: {', '.join(CC_STATES)}.")
    return normalized or None
