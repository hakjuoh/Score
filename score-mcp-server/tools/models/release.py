"""Models for Release tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.common import WhoAndWhen
from tools.models.common import PaginationResponse
from services.models.library import LibraryInfo
from services.models.namespace import NamespaceInfo


class GetReleaseResponse(BaseModel):
    """Response for get_release tool."""
    release_id: int  # Unique identifier for the release
    library: LibraryInfo  # Library information that this release belongs to
    guid: str  # Globally unique identifier for the release
    release_num: str | None  # Release number indicating the version (e.g., "10.0", "10.1", "10.2")
    release_note: str | None  # Release notes describing what changed in this release
    release_license: str | None  # License information for the release (e.g., license text or URL)
    namespace: NamespaceInfo | None  # Default namespace information for this release (if any)
    state: str  # Current state of the release (e.g., "Published", "Draft", "Processing", "Initialized")
    created: WhoAndWhen  # Information about who created the release and when
    last_updated: WhoAndWhen  # Information about who last updated the release and when


class GetReleasePaginationResponse(PaginationResponse[GetReleaseResponse]):
    """Response for get_releases tool."""
    pass

