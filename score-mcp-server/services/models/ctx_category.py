"""Generic models for Context Category domain."""
from pydantic import BaseModel

from services.models.common import WhoAndWhen


class CtxCategorySummary(BaseModel):
    """Context category information."""
    ctx_category_id: int  # Unique identifier for the context category
    name: str  # Name of the context category (e.g., "Geography", "Industry")


class CtxCategoryDto(BaseModel):
    """Context category information with full details."""
    ctx_category_id: int  # Unique identifier for the context category
    guid: str  # Globally unique identifier for the context category
    name: str  # Name of the context category (e.g., "Geography", "Industry", "Product")
    description: str | None  # Description of what the context category represents
    created: WhoAndWhen  # Information about who created the context category and when
    last_updated: WhoAndWhen  # Information about who last updated the context category and when

