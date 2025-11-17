"""Models for Tag tools."""
from __future__ import annotations

from services.models.tag import TagDto
from services.models.common import PaginationResponse


class GetTagPaginationResponse(PaginationResponse[TagDto]):
    """Response for get_tags tool."""
    pass

