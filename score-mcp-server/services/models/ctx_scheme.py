"""Generic models for Context Scheme domain."""
from pydantic import BaseModel

from services.models.common import WhoAndWhen
from services.models.ctx_category import CtxCategorySummary


class CtxSchemeDto(BaseModel):
    """Context scheme information with full details."""
    ctx_scheme_id: int  # Unique identifier for the context scheme
    guid: str  # Globally unique identifier for the context scheme
    scheme_id: str  # Scheme identifier that uniquely identifies the context scheme standard (e.g., "ISO3166-1")
    scheme_name: str | None  # Human-readable name of the context scheme (e.g., "Country Code")
    description: str | None  # Description of what the context scheme represents
    scheme_agency_id: str  # Agency identifier that maintains the scheme (e.g., "ISO", "UN")
    scheme_version_id: str  # Version identifier of the context scheme (e.g., "1.0", "2.1")
    ctx_category: CtxCategorySummary | None  # Context category this scheme belongs to (if any)
    values: list["CtxSchemeValueDto"]  # List of values contained in this context scheme
    created: WhoAndWhen  # Information about who created the context scheme and when
    last_updated: WhoAndWhen  # Information about who last updated the context scheme and when


class CtxSchemeValueDto(BaseModel):
    """Context scheme value information."""
    ctx_scheme_value_id: int  # Unique identifier for the context scheme value
    guid: str  # Globally unique identifier for the context scheme value
    value: str  # The actual value string (e.g., "US", "EUR", "Production")
    meaning: str | None = None  # Human-readable meaning or description of what this value represents


# Update forward references - must be called after all class definitions
CtxSchemeDto.model_rebuild()
