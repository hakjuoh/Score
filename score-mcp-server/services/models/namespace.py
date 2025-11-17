"""Generic models for Namespace domain."""
from pydantic import BaseModel

from services.models.app_user import UserSummary
from services.models.common import WhoAndWhen
from services.models.library import LibrarySummary


class NamespaceSummary(BaseModel):
    """Namespace information object."""
    namespace_id: int  # Unique identifier for the namespace
    prefix: str | None  # Default short name for the URI (if any), used as an XML namespace prefix
    uri: str  # Namespace URI (Uniform Resource Identifier), uniquely identifies the namespace


class NamespaceDto(BaseModel):
    """Namespace information with full details."""
    namespace_id: int  # Unique identifier for the namespace
    library: LibrarySummary  # Library information where this namespace belongs
    uri: str  # Namespace URI (Uniform Resource Identifier), uniquely identifies the namespace
    prefix: str | None  # Default short name for the URI (if any), used as an XML namespace prefix
    description: str | None  # Description of the namespace and its purpose
    is_std_nmsp: bool  # Whether this is a standard namespace reserved for standard use (cannot be modified by users)
    owner: UserSummary  # User information about the owner of the namespace
    created: WhoAndWhen  # Information about who created the namespace and when
    last_updated: WhoAndWhen  # Information about who last updated the namespace and when
