"""Models for Namespace tools."""
from __future__ import annotations

from services.models.namespace import NamespaceDto
from services.models.common import PaginationResponse


class GetNamespaceResponse(NamespaceDto):
    """Response for get_namespace tool."""
    pass


class GetNamespacePaginationResponse(PaginationResponse[GetNamespaceResponse]):
    """Response for get_namespaces tool."""
    pass
