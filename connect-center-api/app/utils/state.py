"""REST lifecycle-state query parsing."""

from __future__ import annotations


def split_state_filter(states: str | None) -> list[str] | None:
    """Split a comma-separated REST value while ignoring empty entries."""
    if not states:
        return None
    parsed = [state.strip() for state in states.split(",") if state.strip()]
    return parsed or None
