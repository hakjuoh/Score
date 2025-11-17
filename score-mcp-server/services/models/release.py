"""Generic models for Release domain."""
from pydantic import BaseModel

from services.models.common import WhoAndWhen
from services.models.library import LibrarySummary
from services.models.namespace import NamespaceSummary


class ReleaseSummary(BaseModel):
    """Release information object."""
    release_id: int  # Unique identifier for the release
    release_num: str | None  # Release number (e.g., "10.0", "10.1"), indicating the version of the release
    state: str  # Current state of the release (e.g., "Published", "Draft", "Processing")


class ReleaseDto(BaseModel):
    """Release information with full details."""
    release_id: int  # Unique identifier for the release
    library: LibrarySummary  # Library information that this release belongs to
    guid: str  # Globally unique identifier for the release
    release_num: str | None  # Release number indicating the version (e.g., "10.0", "10.1", "10.2")
    release_note: str | None  # Release notes describing what changed in this release
    release_license: str | None  # License information for the release (e.g., license text or URL)
    namespace: NamespaceSummary | None  # Default namespace information for this release (if any)
    state: str  # Current state of the release (e.g., "Published", "Draft", "Processing", "Initialized")
    created: WhoAndWhen  # Information about who created the release and when
    last_updated: WhoAndWhen  # Information about who last updated the release and when
