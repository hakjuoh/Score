"""Generic models for Agency ID List domain."""
from pydantic import BaseModel

from services.models.common import UserSummary, WhoAndWhen
from services.models.library import LibrarySummary
from services.models.log import LogInfo
from services.models.namespace import NamespaceSummary
from services.models.release import ReleaseSummary


class AgencyIdListDto(BaseModel):
    """Agency ID list information object."""
    agency_id_list_manifest_id: int  # Unique identifier for the agency ID list manifest (release-specific version)
    agency_id_list_id: int  # Unique identifier for the agency ID list (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    enum_type_guid: str | None  # Enum type GUID associated with this agency ID list (if applicable)
    name: str  # Name of the agency ID list (e.g., "Country Code", "Currency Code")
    list_id: str  # List identifier (e.g., "ISO3166-1", "UNLOCODE") that uniquely identifies the list standard
    version_id: str  # Version identifier of the agency ID list (e.g., "1.0", "2.1")
    definition: str | None  # Definition or description of the agency ID list
    remark: str | None  # Additional remarks or notes about the agency ID list
    definition_source: str | None  # URL indicating the source of the definition (e.g., ISO website)
    namespace: NamespaceSummary | None  # Namespace information if the agency ID list belongs to a specific namespace
    library: LibrarySummary  # Library information where this agency ID list is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    is_deprecated: bool  # Whether the agency ID list is deprecated and should not be used
    state: str | None  # Current state of the agency ID list (e.g., "Published", "Draft", "WIP")
    values: list["AgencyIdListValueDto"]  # List of values contained in this agency ID list
    owner: UserSummary  # User information about the owner of the agency ID list
    created: WhoAndWhen  # Information about who created the agency ID list and when
    last_updated: WhoAndWhen  # Information about who last updated the agency ID list and when


class AgencyIdListValueDto(BaseModel):
    """Agency ID list value information object."""
    agency_id_list_value_manifest_id: int  # Unique identifier for the agency ID list value manifest (release-specific version)
    agency_id_list_value_id: int  # Unique identifier for the agency ID list value (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    value: str  # The actual value string of the agency ID list entry
    name: str | None  # Human-readable name of the agency ID list value (if available)
    definition: str | None  # Definition or description of what this value represents
    is_deprecated: bool  # Whether this agency ID list value is deprecated and should not be used
    is_developer_default: bool  # Whether this is the default value recommended by developers
    is_user_default: bool  # Whether this is the default value selected by end users


# Update forward references - must be called after all class definitions
AgencyIdListDto.model_rebuild()
