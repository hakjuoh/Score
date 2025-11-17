"""Generic models for Agency ID List domain."""
from pydantic import BaseModel


class AgencyIdListValueInfo(BaseModel):
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

