"""Models for Code List tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.code_list import CodeListValueInfo
from tools.models.common import PaginationResponse
from services.models.common import UserInfo, WhoAndWhen
from services.models.library import LibraryInfo
from services.models.log import LogInfo
from services.models.namespace import NamespaceInfo
from services.models.release import ReleaseInfo


class GetCodeListResponse(BaseModel):
    """Response for get_code_list tool."""
    code_list_manifest_id: int  # Unique identifier for the code list manifest (release-specific version)
    code_list_id: int  # Unique identifier for the code list (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    enum_type_guid: str | None  # Enum type GUID associated with this code list (if applicable)
    name: str  # Name of the code list (e.g., "Country Code", "Status Code")
    list_id: str  # List identifier that uniquely identifies the code list standard
    version_id: str  # Version identifier of the code list (e.g., "1.0", "2.1")
    definition: str | None  # Definition or description of the code list
    remark: str | None  # Additional remarks or notes about the code list
    definition_source: str | None  # URL indicating the source of the definition
    namespace: NamespaceInfo | None  # Namespace information if the code list belongs to a specific namespace
    library: LibraryInfo  # Library information where this code list is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    extensible_indicator: bool  # Whether the code list can be extended with additional values by users
    is_deprecated: bool  # Whether the code list is deprecated and should not be used
    state: str | None  # Current state of the code list (e.g., "Published", "Draft", "WIP")
    values: list[CodeListValueInfo]  # List of values contained in this code list
    owner: UserInfo  # User information about the owner of the code list
    created: WhoAndWhen  # Information about who created the code list and when
    last_updated: WhoAndWhen  # Information about who last updated the code list and when


class GetCodeListPaginationResponse(PaginationResponse[GetCodeListResponse]):
    """Response for get_code_lists tool."""
    pass

