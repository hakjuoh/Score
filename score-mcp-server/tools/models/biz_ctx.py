"""Models for Business Context tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.biz_ctx import BizCtxDto
from services.models.common import PaginationResponse


class GetBizCtxResponse(BizCtxDto):
    """Response for get_business_context tool."""
    pass


class GetBizCtxPaginationResponse(PaginationResponse[GetBizCtxResponse]):
    """Response for get_business_contexts tool."""
    pass


class CreateBizCtxResponse(BaseModel):
    """Response for create_business_context tool."""
    biz_ctx_id: int  # Unique identifier of the newly created business context


class CreateBizCtxValueResponse(BaseModel):
    """Response for create_business_context_value tool."""
    biz_ctx_value_id: int  # Unique identifier of the newly created business context value


class UpdateBizCtxResponse(BaseModel):
    """Response for update_business_context tool."""
    biz_ctx_id: int  # Unique identifier of the updated business context
    updates: list[str]  # A list of field names that were updated (e.g., ["name"])


class UpdateBizCtxValueResponse(BaseModel):
    """Response for update_business_context_value tool."""
    biz_ctx_value_id: int  # Unique identifier of the updated business context value
    updates: list[str]  # A list of field names that were updated (e.g., ["value"])


class DeleteBizCtxResponse(BaseModel):
    """Response for delete_business_context tool."""
    biz_ctx_id: int | None = None  # Unique identifier of the deleted business context (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation


class DeleteBizCtxValueResponse(BaseModel):
    """Response for delete_business_context_value tool."""
    biz_ctx_value_id: int | None = None  # Unique identifier of the deleted business context value (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation
