"""Generic models for Business Context domain."""
from pydantic import BaseModel

from services.models.ctx_scheme import CtxSchemeValueInfo


class BusinessContextInfo(BaseModel):
    """Business context information object."""
    biz_ctx_id: int  # Unique identifier for the business context
    guid: str  # Globally unique identifier for the business context
    name: str | None  # Human-readable name of the business context (e.g., "Production Environment", "Test Environment")


class BizCtxValueInfo(BaseModel):
    """Business context value information."""
    biz_ctx_value_id: int  # Unique identifier for the business context value
    ctx_scheme_value: CtxSchemeValueInfo  # The context scheme value associated with this business context value

