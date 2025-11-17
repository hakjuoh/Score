"""Generic models for Code List domain."""
from pydantic import BaseModel


class CodeListValueInfo(BaseModel):
    """Code list value information object."""
    code_list_value_manifest_id: int  # Unique identifier for the code list value manifest (release-specific version)
    code_list_value_id: int  # Unique identifier for the code list value (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    value: str  # The actual code value string (e.g., "US", "EUR", "ACTIVE")
    meaning: str | None  # Human-readable meaning or description of what this code value represents
    definition: str | None  # Detailed definition or explanation of the code list value
    is_deprecated: bool  # Whether this code list value is deprecated and should not be used

