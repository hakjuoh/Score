"""Generic models for Library domain."""
from pydantic import BaseModel


class LibraryInfo(BaseModel):
    """Library information object."""
    library_id: int  # Unique identifier for the library
    name: str  # Library name (e.g., "connectSpec")

