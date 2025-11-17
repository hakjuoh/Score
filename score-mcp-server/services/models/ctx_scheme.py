"""Generic models for Context Scheme domain."""
from pydantic import BaseModel


class CtxSchemeValueInfo(BaseModel):
    """Context scheme value information."""
    ctx_scheme_value_id: int  # Unique identifier for the context scheme value
    guid: str  # Globally unique identifier for the context scheme value
    value: str  # The actual value string (e.g., "US", "EUR", "Production")
    meaning: str | None = None  # Human-readable meaning or description of what this value represents

