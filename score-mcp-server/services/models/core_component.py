"""Generic models for Core Component domain."""
from typing import Literal, Optional

from pydantic import BaseModel, computed_field

from services.models.common import ValueConstraint
from services.models.data_type import DtInfo
from services.models.library import LibraryInfo
from services.models.namespace import NamespaceInfo
from services.models.release import ReleaseInfo


class AsccpInfo(BaseModel):
    """ASCCP (Association Core Component Property) information object."""
    asccp_manifest_id: int  # Unique identifier for the ASCCP manifest (release-specific version)
    asccp_id: int  # Unique identifier for the ASCCP (base entity ID)
    role_of_acc_manifest_id: int  # Unique identifier for the ACC manifest that this ASCCP plays the role of
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    property_term: str  # Property term as specified in CCTS v3, part of the component name
    definition: str | None  # Definition or description of the ASCCP
    definition_source: str | None  # URL indicating the source of the definition
    is_deprecated: bool  # Whether the ASCCP is deprecated and should not be used


class BccpInfo(BaseModel):
    """BCCP (Basic Core Component Property) information object."""
    bccp_manifest_id: int  # Unique identifier for the BCCP manifest (release-specific version)
    bccp_id: int  # Unique identifier for the BCCP (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    property_term: str  # Property term as specified in CCTS v3, part of the component name
    representation_term: str  # Representation term as specified in CCTS v3, indicates the data format
    definition: str | None  # Definition or description of the BCCP
    definition_source: str | None  # URL indicating the source of the definition
    bdt_manifest: DtInfo  # Basic Data Type (BDT) information associated with this BCCP
    is_deprecated: bool  # Whether the BCCP is deprecated and should not be used


class AccInfo(BaseModel):
    """ACC (Aggregation Core Component) information object."""
    acc_manifest_id: int  # Unique identifier for the ACC manifest (release-specific version)
    acc_id: int  # Unique identifier for the ACC (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    object_class_term: str  # Object class term as specified in CCTS v3, part of the component name
    definition: str | None  # Definition or description of the ACC
    definition_source: str | None  # URL indicating the source of the definition
    is_deprecated: bool  # Whether the ACC is deprecated and should not be used


class AsccInfo(BaseModel):
    """ASCC (Association Core Component) information object."""
    ascc_manifest_id: int  # Unique identifier for the ASCC manifest (release-specific version)
    ascc_id: int  # Unique identifier for the ASCC (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    is_deprecated: bool  # Whether the ASCC is deprecated and should not be used
    definition: str | None  # Definition or description of the ASCC
    definition_source: str | None  # URL indicating the source of the definition
    from_acc_manifest_id: int  # Unique identifier for the source ACC manifest (the ACC that contains this ASCC)
    to_asccp_manifest_id: int  # Unique identifier for the target ASCCP manifest (the ASCCP this ASCC connects to)


class BccInfo(BaseModel):
    """BCC (Basic Core Component) information object."""
    bcc_manifest_id: int  # Unique identifier for the BCC manifest (release-specific version)
    bcc_id: int  # Unique identifier for the BCC (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    entity_type: str | None  # Entity type: "Attribute" (XML attribute) or "Element" (XML element)
    is_nillable: bool  # Whether the BCC can have a nil/null value
    is_deprecated: bool  # Whether the BCC is deprecated and should not be used
    definition: str | None  # Definition or description of the BCC
    definition_source: str | None  # URL indicating the source of the definition
    from_acc_manifest_id: int  # Unique identifier for the source ACC manifest (the ACC that contains this BCC)
    to_bccp_manifest_id: int  # Unique identifier for the target BCCP manifest (the BCCP this BCC connects to)


class AccRelationshipInfo(BaseModel):
    """Base related component information object."""
    component_type: Literal["ASCC", "BCC"]  # Type of related component: "ASCC" (Association) or "BCC" (Basic)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    is_deprecated: bool  # Whether the component is deprecated and should not be used
    definition: str | None  # Definition or description of the component
    definition_source: str | None  # URL indicating the source of the definition
    from_acc: AccInfo  # Information about the ACC that contains this related component

    @computed_field
    @property
    def cardinality_display(self) -> str:
        """Display cardinality with 'unbounded' for -1 values."""
        if self.cardinality_max == -1:
            return f"{self.cardinality_min}..unbounded"
        else:
            return f"{self.cardinality_min}..{self.cardinality_max}"


class AsccRelationshipInfo(AccRelationshipInfo):
    """ASCC related component information object."""
    component_type: Literal["ASCC"] = "ASCC"  # Type of related component, always "ASCC" for this class
    ascc_manifest_id: int  # Unique identifier for the ASCC manifest (release-specific version)
    ascc_id: int  # Unique identifier for the ASCC (base entity ID)
    to_asccp: AsccpInfo  # Information about the ASCCP that this ASCC connects to

    @computed_field
    @property
    def manifest_id(self) -> int:
        return self.ascc_manifest_id


class BccRelationshipInfo(AccRelationshipInfo):
    """BCC related component information object."""
    component_type: Literal["BCC"] = "BCC"  # Type of related component, always "BCC" for this class
    bcc_manifest_id: int  # Unique identifier for the BCC manifest (release-specific version)
    bcc_id: int  # Unique identifier for the BCC (base entity ID)
    entity_type: Optional[Literal["Attribute", "Element"]] = None  # Entity type: "Attribute" (XML attribute) or "Element" (XML element)
    is_nillable: bool  # Whether the BCC can have a nil/null value
    value_constraint: ValueConstraint | None  # Value constraint (default_value or fixed_value) for the BCC
    to_bccp: BccpInfo  # Information about the BCCP that this BCC connects to

    @computed_field
    @property
    def manifest_id(self) -> int:
        return self.bcc_manifest_id


class BaseAccInfo(BaseModel):
    """Base ACC information object."""
    acc_manifest_id: int  # Unique identifier for the base ACC manifest (release-specific version)
    acc_id: int  # Unique identifier for the base ACC (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    object_class_term: str  # Object class term as specified in CCTS v3, part of the component name
    type: str  # Type of ACC (e.g., "Default", "Extension", "SemanticGroup")
    definition: str | None  # Definition or description of the ACC
    definition_source: str | None  # URL indicating the source of the definition
    namespace: NamespaceInfo | None  # Namespace information if the ACC belongs to a specific namespace
    library: LibraryInfo  # Library information where this ACC is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to


class BaseAsccpInfo(BaseModel):
    """Base ASCCP information object."""
    asccp_manifest_id: int  # Unique identifier for the base ASCCP manifest (release-specific version)
    asccp_id: int  # Unique identifier for the base ASCCP (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str | None  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    property_term: str | None  # Property term as specified in CCTS v3, part of the component name
    type: str  # Type of ASCCP (e.g., "Default", "Extension")
    definition: str | None  # Definition or description of the ASCCP
    definition_source: str | None  # URL indicating the source of the definition
    namespace: NamespaceInfo | None  # Namespace information if the ASCCP belongs to a specific namespace
    library: LibraryInfo  # Library information where this ASCCP is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to


class BaseBccpInfo(BaseModel):
    """Base BCCP information object."""
    bccp_manifest_id: int  # Unique identifier for the base BCCP manifest (release-specific version)
    bccp_id: int  # Unique identifier for the base BCCP (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    property_term: str  # Property term as specified in CCTS v3, part of the component name
    representation_term: str  # Representation term as specified in CCTS v3, indicates the data format
    definition: str | None  # Definition or description of the BCCP
    definition_source: str | None  # URL indicating the source of the definition
    namespace: NamespaceInfo | None  # Namespace information if the BCCP belongs to a specific namespace
    library: LibraryInfo  # Library information where this BCCP is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to

