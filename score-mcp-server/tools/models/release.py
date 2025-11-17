"""Models for Release tools."""
from __future__ import annotations

from services.models.release import ReleaseDto
from services.models.common import PaginationResponse


class GetReleaseResponse(ReleaseDto):
    """Response for get_release tool."""
    pass


class GetReleasePaginationResponse(PaginationResponse[GetReleaseResponse]):
    """Response for get_releases tool."""
    pass
