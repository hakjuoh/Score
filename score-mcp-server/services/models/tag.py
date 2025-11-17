"""Generic models for Tag domain."""
from pydantic import BaseModel

from services.models.common import WhoAndWhen


class TagInfo(BaseModel):
    """Tag information object."""
    tag_id: int  # Unique identifier for the tag
    name: str  # Tag name (e.g., "BOD" for Business Object Document, "Noun", "Verb")
    description: str | None  # Description of what the tag represents or is used for
    color: str | None  # Background color code for the tag (typically hex color code like "#FF5733")
    text_color: str | None  # Text color code for the tag (typically hex color code like "#FFFFFF")
    created: WhoAndWhen  # Information about who created the tag and when
    last_updated: WhoAndWhen  # Information about who last updated the tag and when

