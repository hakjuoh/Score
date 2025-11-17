"""Generic models for Namespace domain."""
from pydantic import BaseModel


class NamespaceInfo(BaseModel):
    """Namespace information object."""
    namespace_id: int  # Unique identifier for the namespace
    prefix: str | None  # Default short name for the URI (if any), used as an XML namespace prefix
    uri: str  # Namespace URI (Uniform Resource Identifier), uniquely identifies the namespace

