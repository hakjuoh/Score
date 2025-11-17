"""Models for App User tools."""
from __future__ import annotations

from pydantic import BaseModel

from tools.models.common import PaginationResponse


class GetUserResponse(BaseModel):
    """Response for get_users tool."""
    user_id: int  # Unique identifier for the user
    login_id: str  # User's login identifier used for authentication
    username: str | None  # Display name of the user (human-readable name)
    organization: str | None  # The company or organization the user represents
    email: str | None  # Email address of the user
    roles: list[str]  # List of roles assigned to the user (Admin, Developer, End-User)
    is_enabled: bool  # Whether the user account is enabled and can access the system


class GetUserPaginationResponse(PaginationResponse[GetUserResponse]):
    """Response for get_users tool."""
    pass

