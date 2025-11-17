"""Models for Business Context tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.biz_ctx import BizCtxValueInfo
from tools.models.common import PaginationResponse
from services.models.common import WhoAndWhen


class CreateBizCtxResponse(BaseModel):
    """Response for create_business_context tool."""
    biz_ctx_id: int  # Unique identifier of the newly created business context


class CreateBizCtxValueResponse(BaseModel):
    """Response for create_business_context_value tool."""
    biz_ctx_value_id: int  # Unique identifier of the newly created business context value


class UpdateBizCtxValueResponse(BaseModel):
    """Response for update_business_context_value tool."""
    biz_ctx_value_id: int  # Unique identifier of the updated business context value
    updates: list[str]  # A list of field names that were updated (e.g., ["value"])


class DeleteBizCtxValueResponse(BaseModel):
    """Response for delete_business_context_value tool."""
    biz_ctx_value_id: int | None = None  # Unique identifier of the deleted business context value (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation


class UpdateBizCtxResponse(BaseModel):
    """Response for update_business_context tool."""
    biz_ctx_id: int  # Unique identifier of the updated business context
    updates: list[str]  # A list of field names that were updated (e.g., ["name"])


class DeleteBizCtxResponse(BaseModel):
    """Response for delete_business_context tool."""
    biz_ctx_id: int | None = None  # Unique identifier of the deleted business context (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation


class GetBizCtxResponse(BaseModel):
    """Response for get_business_context tool."""
    biz_ctx_id: int  # Unique identifier for the business context
    guid: str  # Globally unique identifier for the business context
    name: str | None  # Human-readable name of the business context (e.g., "Production Environment", "Test Environment")
    values: list[BizCtxValueInfo]  # List of context scheme values assigned to this business context
    created: WhoAndWhen  # Information about who created the business context and when
    last_updated: WhoAndWhen  # Information about who last updated the business context and when


class GetBizCtxPaginationResponse(PaginationResponse[GetBizCtxResponse]):
    """Response for get_business_contexts tool."""
    pass

