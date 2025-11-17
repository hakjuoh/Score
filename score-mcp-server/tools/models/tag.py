"""Models for Tag tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.tag import TagInfo
from tools.models.common import PaginationResponse


class GetTagPaginationResponse(PaginationResponse[TagInfo]):
    """Response for get_tags tool."""
    pass

