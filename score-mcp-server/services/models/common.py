"""
Shared models and data classes used across services.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import TypeVar
from typing import Union, Generic

from pydantic import BaseModel, field_serializer, field_validator, model_validator


@dataclass
class Sort:
    """Sort specification for ordering."""
    column: str
    direction: str  # 'asc' or 'desc'

    def __post_init__(self):
        """Validate sort direction."""
        if self.direction not in ['asc', 'desc']:
            raise ValueError(f"Invalid sort direction: {self.direction}. Must be 'asc' or 'desc'")


@dataclass
class PaginationParams:
    """Pagination parameters for database queries."""
    offset: int
    limit: int

    def __post_init__(self):
        """Validate pagination parameters."""
        if self.offset < 0:
            raise ValueError(f"Offset must be non-negative, got: {self.offset}")
        if self.limit < 1:
            raise ValueError(f"Limit must be at least 1, got: {self.limit}")
        if self.limit > 100:
            raise ValueError(f"Limit cannot exceed 100, got: {self.limit}")


@dataclass
class DateRangeParams:
    """Date range parameters for filtering database queries."""
    before: datetime | None = None
    after: datetime | None = None

    def __post_init__(self):
        """Validate date range parameters."""
        if self.before is not None and self.after is not None and self.before >= self.after:
            raise ValueError("Before date must be earlier than after date")


T = TypeVar('T')


class PaginationResponse(BaseModel, Generic[T]):
    """Response model for paginated data."""
    total_items: int  # Total number of items available
    offset: int  # Number of items to skip from the beginning
    limit: int  # Maximum number of items to return
    items: list[T]  # List of items in the current page


# Import Info models from their respective domain modules
from services.models.app_user import UserSummary


class WhoAndWhen(BaseModel):
    """Who and when information object."""
    who: UserSummary  # Information about the user who performed the action
    when: datetime  # RFC 3339 timestamp

    @field_validator('when', mode='before')
    @classmethod
    def parse_when(cls, v: Union[datetime, str]) -> datetime:
        """Parse datetime string (YYYY-MM-DDTHH:MM:SSZ format) to datetime object."""
        if isinstance(v, str):
            # Handle Z suffix (UTC indicator)
            if v.endswith('Z'):
                v = v[:-1] + '+00:00'
            # Parse ISO format string to datetime
            return datetime.fromisoformat(v)
        return v

    @field_serializer('when')
    def serialize_when(self, dt: datetime, _info) -> str:
        """Serialize datetime to RFC 3339 format string with Z suffix for UTC."""
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        # Convert to UTC if timezone-aware
        if dt.tzinfo != timezone.utc:
            dt = dt.astimezone(timezone.utc)
        # Format as YYYY-MM-DDTHH:MM:SSZ
        return dt.strftime('%Y-%m-%dT%H:%M:%SZ')


class ValueConstraint(BaseModel):
    """Value constraint information for components.

    Validation rules:
    - Can be None (optional)
    - If not None, exactly one of default_value or fixed_value must be set (not both, not neither)
    """
    default_value: str | None  # Default value for the component
    fixed_value: str | None  # Fixed value for the component

    @model_validator(mode='after')
    def validate_value_constraint(self):
        """Validate that exactly one of default_value or fixed_value is set."""
        has_default = self.default_value is not None
        has_fixed = self.fixed_value is not None

        if has_default and has_fixed:
            raise ValueError(
                "ValueConstraint: Both default_value and fixed_value cannot be set. "
                "Exactly one must be set, the other must be None."
            )
        if not has_default and not has_fixed:
            raise ValueError(
                "ValueConstraint: Either default_value or fixed_value must be set. "
                "Both cannot be None."
            )

        return self
