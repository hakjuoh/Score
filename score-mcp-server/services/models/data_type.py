"""Generic models for Data Type domain."""
from pydantic import BaseModel

from services.models.common import ValueConstraint
from services.models.library import LibraryInfo
from services.models.namespace import NamespaceInfo
from services.models.release import ReleaseInfo


class DtInfo(BaseModel):
    """DT (Data Type) information object."""
    dt_manifest_id: int  # Unique identifier for the data type manifest (release-specific version)
    dt_id: int  # Unique identifier for the data type (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    data_type_term: str  # Data type term as specified in CCTS v3
    qualifier: str | None  # Qualifier that modifies the data type term (if any)
    representation_term: str | None  # Representation term as specified in CCTS v3, indicates the data format
    six_digit_id: str | None  # Six-digit identifier used in some data type catalogues for classification
    based_dt_manifest_id: int  # Unique identifier for the base data type manifest this DT is derived from (if any)
    definition: str | None  # Definition or description of the data type
    definition_source: str | None  # URL indicating the source of the definition
    is_deprecated: bool  # Whether the data type is deprecated and should not be used


class DtScInfo(BaseModel):
    """DT_SC (Data Type Supplementary Component) information object."""
    dt_sc_manifest_id: int  # Unique identifier for the data type supplementary component manifest (release-specific version)
    dt_sc_id: int  # Unique identifier for the data type supplementary component (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    object_class_term: str | None  # Object class term as specified in CCTS v3 (part of the supplementary component name)
    property_term: str | None  # Property term as specified in CCTS v3 (part of the supplementary component name)
    representation_term: str | None  # Representation term as specified in CCTS v3 (part of the supplementary component name)
    definition: str | None  # Definition or description of the supplementary component
    definition_source: str | None  # URL indicating the source of the definition
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int | None  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded, None indicates unbounded)
    value_constraint: ValueConstraint | None  # Value constraint (default_value or fixed_value) for the supplementary component
    is_deprecated: bool  # Whether the supplementary component is deprecated and should not be used


class BaseDtInfo(BaseModel):
    """Base Data Type information object."""
    dt_manifest_id: int # Unique identifier for the base data type manifest (release-specific version)
    dt_id: int  # Unique identifier for the base data type (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - the standardized name of the data type as defined by CCTS v3
    data_type_term: str | None  # Data type term as specified in CCTS v3
    qualifier: str | None  # Qualifier that modifies the data type term (if any)
    representation_term: str | None  # Representation term as specified in CCTS v3
    six_digit_id: str | None  # Six-digit identifier used in some data type catalogues for classification
    definition: str | None  # Definition or description of the data type
    definition_source: str | None  # URL indicating the source of the definition
    content_component_definition: str | None  # Definition of the content component part of the data type
    namespace: NamespaceInfo | None  # Namespace information if the data type belongs to a specific namespace
    library: LibraryInfo  # Library information where this data type is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to

