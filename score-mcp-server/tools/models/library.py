"""Models for Library tools."""
from __future__ import annotations

from services.models.library import LibraryDto
from services.models.common import PaginationResponse


class GetLibraryResponse(LibraryDto):
    """Response for get_library tool."""
    pass


class GetLibraryPaginationResponse(PaginationResponse[GetLibraryResponse]):
    """Response for get_libraries tool."""
    pass

