"""Generic models for Release domain."""
from pydantic import BaseModel


class ReleaseInfo(BaseModel):
    """Release information object."""
    release_id: int  # Unique identifier for the release
    release_num: str | None  # Release number (e.g., "10.0", "10.1"), indicating the version of the release
    state: str  # Current state of the release (e.g., "Published", "Draft", "Processing")

