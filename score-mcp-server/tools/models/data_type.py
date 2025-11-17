"""Models for Data Type tools."""
from __future__ import annotations

from services.models.data_type import DtDto
from services.models.common import PaginationResponse


class GetDataTypeResponse(DtDto):
    """Response for get_data_type tool."""
    pass


class GetDataTypePaginationResponse(PaginationResponse[GetDataTypeResponse]):
    """Response for get_data_types tool."""
    pass
