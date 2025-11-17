"""Models for Data Type tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.common import UserInfo, WhoAndWhen
from tools.models.common import PaginationResponse
from services.models.data_type import BaseDtInfo, DtScInfo
from services.models.library import LibraryInfo
from services.models.log import LogInfo
from services.models.namespace import NamespaceInfo
from services.models.release import ReleaseInfo


class GetDataTypeResponse(BaseModel):
    """Response for get_data_type tool."""
    dt_manifest_id: int  # Unique identifier for the data type manifest (release-specific version)
    dt_id: int  # Unique identifier for the data type (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name of the data type as defined by CCTS v3
    data_type_term: str | None  # Data type term as specified in CCTS v3
    qualifier: str | None  # Qualifier that modifies the data type term (if any)
    representation_term: str | None  # Representation term as specified in CCTS v3
    six_digit_id: str | None  # Six-digit identifier used in some data type catalogues for classification
    definition: str | None  # Definition or description of the data type
    definition_source: str | None  # URL indicating the source of the definition
    content_component_definition: str | None  # Definition of the content component part of the data type
    namespace: NamespaceInfo | None  # Namespace information if the data type belongs to a specific namespace
    library: LibraryInfo  # Library information where this data type is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    commonly_used: bool  # Whether this data type is commonly used across the system
    is_deprecated: bool  # Whether the data type is deprecated and should not be used
    state: str | None  # Current state of the data type (e.g., "Published", "Draft", "WIP")
    base_dt: BaseDtInfo | None  # Base data type information if this data type is derived from another
    supplementary_components: list[DtScInfo]  # List of supplementary components associated with this data type
    owner: UserInfo  # User information about the owner of the data type
    created: WhoAndWhen  # Information about who created the data type and when
    last_updated: WhoAndWhen  # Information about who last updated the data type and when


class GetDataTypePaginationResponse(PaginationResponse[GetDataTypeResponse]):
    """Response for get_data_types tool."""
    pass

