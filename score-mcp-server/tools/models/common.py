"""Common models for MCP tools."""
from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel

T = TypeVar('T')


class PaginationResponse(BaseModel, Generic[T]):
    """Response model for paginated data."""
    total_items: int  # Total number of items available
    offset: int  # Number of items to skip from the beginning
    limit: int  # Maximum number of items to return
    items: list[T]  # List of items in the current page

