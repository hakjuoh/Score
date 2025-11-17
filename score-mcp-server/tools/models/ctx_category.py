"""Models for Context Category tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.common import WhoAndWhen
from tools.models.common import PaginationResponse


class CreateCtxCategoryResponse(BaseModel):
    """Response for create_ctx_category tool."""
    ctx_category_id: int  # Unique identifier of the newly created context category


class GetCtxCategoryResponse(BaseModel):
    """Response for get_ctx_category tool."""
    ctx_category_id: int  # Unique identifier for the context category
    guid: str  # Globally unique identifier for the context category
    name: str  # Name of the context category (e.g., "Geography", "Industry", "Product")
    description: str | None  # Description of what the context category represents
    created: WhoAndWhen  # Information about who created the context category and when
    last_updated: WhoAndWhen  # Information about who last updated the context category and when


class UpdateCtxCategoryResponse(BaseModel):
    """Response for update_context_category tool."""
    ctx_category_id: int  # Unique identifier of the updated context category
    updates: list[str]  # A list of field names that were updated (e.g., ["name", "description"])


class DeleteCtxCategoryResponse(BaseModel):
    """Response for delete_ctx_category tool."""
    ctx_category_id: int | None = None  # Unique identifier of the deleted context category (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation


class GetCtxCategoryPaginationResponse(PaginationResponse[GetCtxCategoryResponse]):
    """Response for get_ctx_categories tool."""
    pass

