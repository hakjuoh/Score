"""Models for Context Category tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.ctx_category import CtxCategoryDto
from services.models.common import PaginationResponse


class GetCtxCategoryResponse(CtxCategoryDto):
    """Response for get_ctx_category tool."""
    pass


class GetCtxCategoryPaginationResponse(PaginationResponse[GetCtxCategoryResponse]):
    """Response for get_ctx_categories tool."""
    pass


class CreateCtxCategoryResponse(BaseModel):
    """Response for create_ctx_category tool."""
    ctx_category_id: int  # Unique identifier of the newly created context category


class UpdateCtxCategoryResponse(BaseModel):
    """Response for update_context_category tool."""
    ctx_category_id: int  # Unique identifier of the updated context category
    updates: list[str]  # A list of field names that were updated (e.g., ["name", "description"])


class DeleteCtxCategoryResponse(BaseModel):
    """Response for delete_ctx_category tool."""
    ctx_category_id: int | None = None  # Unique identifier of the deleted context category (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation
