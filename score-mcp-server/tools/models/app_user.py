"""Models for App User tools."""
from __future__ import annotations

from services.models.app_user import UserDto
from services.models.common import PaginationResponse


class GetUserResponse(UserDto):
    """Response for get_users tool."""
    pass


class GetUserPaginationResponse(PaginationResponse[GetUserResponse]):
    """Response for get_users tool."""
    pass
