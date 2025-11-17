"""Generic models for XBT (XML Built-in Type) domain."""
from pydantic import BaseModel

from services.models.library import LibraryInfo
from services.models.release import ReleaseInfo


class SubtypeOfXbtInfo(BaseModel):
    """Subtype of XBT information object.
    
    Represents the parent XBT in the type hierarchy. XBTs can be subtypes of other XBTs,
    forming a specialization hierarchy. For example, 'normalizedString' is a subtype of 'string',
    and 'integer' is a subtype of 'decimal'. The root type is typically 'anyType', with 'anySimpleType'
    as its direct subtype.
    
    Attributes:
        xbt_manifest_id: Unique identifier for the subtype XBT manifest (release-specific version)
        xbt_id: Unique identifier for the subtype XBT (base entity ID, same across all releases)
        guid: Globally unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
        name: Human-readable name of the built-in type (e.g., "string", "date time", "boolean")
        builtIn_type: Built-in type as it should appear in XML schema with namespace prefix (e.g., "xsd:string", "xsd:dateTime")
        library: Library information where this XBT is stored
        release: Release information indicating which release this version belongs to
    """
    xbt_manifest_id: int  # Unique identifier for the subtype XBT manifest (release-specific version)
    xbt_id: int  # Unique identifier for the subtype XBT (base entity ID, same across all releases)
    guid: str  # Globally unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    name: str | None  # Human-readable name of the built-in type (e.g., "string", "date time", "boolean")
    builtIn_type: str | None  # Built-in type as it should appear in XML schema with namespace prefix (e.g., "xsd:string", "xsd:dateTime")
    library: LibraryInfo  # Library information where this XBT is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to

