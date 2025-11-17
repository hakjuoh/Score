"""Generic models for Context Category domain."""
from pydantic import BaseModel


class CtxCategoryInfo(BaseModel):
    """Context category information."""
    ctx_category_id: int  # Unique identifier for the context category
    name: str  # Name of the context category (e.g., "Geography", "Industry")

