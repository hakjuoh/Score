"""Models for Context Scheme tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.ctx_scheme import CtxSchemeDto
from services.models.common import PaginationResponse


class GetCtxSchemeResponse(CtxSchemeDto):
    """Response for get_ctx_schemes tool."""
    pass


class GetCtxSchemePaginationResponse(PaginationResponse[GetCtxSchemeResponse]):
    """Response for get_ctx_schemes tool."""
    pass


class CreateCtxSchemeResponse(BaseModel):
    """Response for create_ctx_scheme tool."""
    ctx_scheme_id: int  # Unique identifier of the newly created context scheme


class CreateCtxSchemeValueResponse(BaseModel):
    """Response for create_ctx_scheme_value tool."""
    ctx_scheme_value_id: int  # Unique identifier of the newly created context scheme value


class UpdateCtxSchemeResponse(BaseModel):
    """Response for update_context_scheme tool."""
    ctx_scheme_id: int  # Unique identifier of the updated context scheme
    updates: list[str]  # A list of field names that were updated (e.g., ["scheme_name", "description"])


class UpdateCtxSchemeValueResponse(BaseModel):
    """Response for update_context_scheme_value tool."""
    ctx_scheme_value_id: int  # Unique identifier of the updated context scheme value
    updates: list[str]  # A list of field names that were updated (e.g., ["value", "meaning"])


class DeleteCtxSchemeResponse(BaseModel):
    """Response for delete_ctx_scheme tool."""
    ctx_scheme_id: int | None = None  # Unique identifier of the deleted context scheme (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation


class DeleteCtxSchemeValueResponse(BaseModel):
    """Response for delete_context_scheme_value tool."""
    ctx_scheme_value_id: int | None = None  # Unique identifier of the deleted context scheme value (None if deletion was cancelled)
    message: str | None = None  # Optional message indicating the status of the deletion operation
