"""Generic models for Code List domain."""
from pydantic import BaseModel

from services.models.app_user import UserSummary
from services.models.common import WhoAndWhen
from services.models.library import LibrarySummary
from services.models.log import LogInfo
from services.models.namespace import NamespaceSummary
from services.models.release import ReleaseSummary


class CodeListDto(BaseModel):
    """Code list information object."""
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
    namespace: NamespaceSummary | None  # Namespace information if the code list belongs to a specific namespace
    library: LibrarySummary  # Library information where this code list is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    extensible_indicator: bool  # Whether the code list can be extended with additional values by users
    is_deprecated: bool  # Whether the code list is deprecated and should not be used
    state: str | None  # Current state of the code list (e.g., "Published", "Draft", "WIP")
    values: list["CodeListValueDto"]  # List of values contained in this code list
    owner: UserSummary  # User information about the owner of the code list
    created: WhoAndWhen  # Information about who created the code list and when
    last_updated: WhoAndWhen  # Information about who last updated the code list and when


class CodeListValueDto(BaseModel):
    """Code list value information object."""
    code_list_value_manifest_id: int  # Unique identifier for the code list value manifest (release-specific version)
    code_list_value_id: int  # Unique identifier for the code list value (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    value: str  # The actual code value string (e.g., "US", "EUR", "ACTIVE")
    meaning: str | None  # Human-readable meaning or description of what this code value represents
    definition: str | None  # Detailed definition or explanation of the code list value
    is_deprecated: bool  # Whether this code list value is deprecated and should not be used


# Update forward references - must be called after all class definitions
CodeListDto.model_rebuild()
