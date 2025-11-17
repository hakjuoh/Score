"""Models for Code List tools."""
from __future__ import annotations

from services.models.code_list import CodeListDto
from services.models.common import PaginationResponse


class GetCodeListResponse(CodeListDto):
    """Response for get_code_list tool."""
    pass


class GetCodeListPaginationResponse(PaginationResponse[GetCodeListResponse]):
    """Response for get_code_lists tool."""
    pass
