"""Generic models for Business Context domain."""
from pydantic import BaseModel

from services.models.common import WhoAndWhen
from services.models.ctx_scheme import CtxSchemeValueDto


class BizCtxDto(BaseModel):
    """Business context information object with full details."""
    biz_ctx_id: int  # Unique identifier for the business context
    guid: str  # Globally unique identifier for the business context
    name: str | None  # Human-readable name of the business context (e.g., "Production Environment", "Test Environment")
    values: list["BizCtxValueDto"]  # List of context scheme values assigned to this business context
    created: WhoAndWhen  # Information about who created the business context and when
    last_updated: WhoAndWhen  # Information about who last updated the business context and when


class BizCtxValueDto(BaseModel):
    """Business context value information."""
    biz_ctx_value_id: int  # Unique identifier for the business context value
    ctx_scheme_value: CtxSchemeValueDto  # The context scheme value associated with this business context value


class BizCtxSummary(BaseModel):
    """Business context information object."""
    biz_ctx_id: int  # Unique identifier for the business context
    guid: str  # Globally unique identifier for the business context
    name: str | None  # Human-readable name of the business context (e.g., "Production Environment", "Test Environment")


# Update forward references - must be called after all class definitions
BizCtxDto.model_rebuild()
