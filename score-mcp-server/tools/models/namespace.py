"""Models for Namespace tools."""
from __future__ import annotations

from pydantic import BaseModel

from services.models.common import UserInfo, WhoAndWhen
from tools.models.common import PaginationResponse
from services.models.library import LibraryInfo


class GetNamespaceResponse(BaseModel):
    """Response for get_namespace tool."""
    namespace_id: int  # Unique identifier for the namespace
    library: LibraryInfo  # Library information where this namespace belongs
    uri: str  # Namespace URI (Uniform Resource Identifier), uniquely identifies the namespace
    prefix: str | None  # Default short name for the URI (if any), used as an XML namespace prefix
    description: str | None  # Description of the namespace and its purpose
    is_std_nmsp: bool  # Whether this is a standard namespace reserved for standard use (cannot be modified by users)
    owner: UserInfo  # User information about the owner of the namespace
    created: WhoAndWhen  # Information about who created the namespace and when
    last_updated: WhoAndWhen  # Information about who last updated the namespace and when


class GetNamespacePaginationResponse(PaginationResponse[GetNamespaceResponse]):
    """Response for get_namespaces tool."""
    pass

