"""Generic models for App User domain."""
from pydantic import BaseModel


class UserSummary(BaseModel):
    """User information object."""
    user_id: int  # Unique identifier for the user
    login_id: str  # User's login identifier
    username: str  # Display name of the user
    roles: list[str]  # List of roles assigned to the user. Three types of roles are supported:
    # - Admin: Administrator that can control system configurations, data ownership management, and library/release creation
    # - Developer: Can create/edit core components for the library
    # - End-User: Can utilize core components for profiling business information entities


class UserDto(BaseModel):
    """Response for get_users tool."""
    user_id: int  # Unique identifier for the user
    login_id: str  # User's login identifier used for authentication
    username: str | None  # Display name of the user (human-readable name)
    organization: str | None  # The company or organization the user represents
    email: str | None  # Email address of the user
    roles: list[str]  # List of roles assigned to the user (Admin, Developer, End-User)
    is_enabled: bool  # Whether the user account is enabled and can access the system
