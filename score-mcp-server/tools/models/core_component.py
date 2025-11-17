"""Models for Core Component tools."""
from __future__ import annotations

from typing import Literal, List, Union

from pydantic import BaseModel

from tools.models.common import PaginationResponse

from services.models.common import UserInfo, WhoAndWhen
from services.models.core_component import (
    AsccRelationshipInfo,
    BaseAccInfo,
    BccRelationshipInfo,
    ValueConstraint,
)
from services.models.data_type import BaseDtInfo
from services.models.library import LibraryInfo
from services.models.log import LogInfo
from services.models.namespace import NamespaceInfo
from services.models.release import ReleaseInfo


# ACC Response Models
class GetAccResponse(BaseModel):
    """Response for get_acc tool."""
    acc_manifest_id: int  # Unique identifier for the ACC manifest (release-specific version)
    acc_id: int  # Unique identifier for the ACC (base entity ID)
    base_acc: BaseAccInfo | None  # Base ACC information if this ACC is derived from another
    relationships: List[Union[AsccRelationshipInfo, BccRelationshipInfo]]  # List of related components (ASCCs and BCCs) contained in the ACC
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - following rule: acc.object_class_term + ". Details"
    object_class_term: str  # Object class term as specified in CCTS v3, part of the component name
    definition: str | None  # Definition or description of the ACC
    definition_source: str | None  # URL indicating the source of the definition
    object_class_qualifier: str | None  # Qualifier that modifies the object class term (if any)
    component_type: int | None  # Numeric component type identifier (e.g., 0=Default, 3=SemanticGroup, 4=UserExtensionGroup)
    is_abstract: bool  # Whether the ACC is abstract (cannot be instantiated directly)
    is_deprecated: bool  # Whether the ACC is deprecated and should not be used
    state: str | None  # Current state of the ACC (e.g., "Published", "Draft", "WIP", "QA", "Candidate", "Production")
    namespace: NamespaceInfo | None  # Namespace information if the ACC belongs to a specific namespace
    library: LibraryInfo  # Library information where this ACC is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserInfo  # User information about the owner of the ACC
    created: WhoAndWhen  # Information about who created the ACC and when
    last_updated: WhoAndWhen  # Information about who last updated the ACC and when


# ASCCP Response Models
class GetAsccpResponse(BaseModel):
    """Response for get_asccp tool."""
    asccp_manifest_id: int  # Unique identifier for the ASCCP manifest (release-specific version)
    asccp_id: int  # Unique identifier for the ASCCP (base entity ID)
    role_of_acc: BaseAccInfo  # Information about the ACC that this ASCCP plays the role of (required)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str | None  # Dictionary Entry Name (DEN) - following rule: asccp.property_term + ". " + asccp.role_of_acc.object_class_term
    property_term: str | None  # Property term as specified in CCTS v3, part of the component name
    definition: str | None  # Definition or description of the ASCCP
    definition_source: str | None  # URL indicating the source of the definition
    reusable_indicator: bool  # Whether the ASCCP can be reused in multiple contexts
    is_nillable: bool | None  # Whether the ASCCP can have a nil/null value
    is_deprecated: bool  # Whether the ASCCP is deprecated and should not be used
    state: str | None  # Current state of the ASCCP (e.g., "Published", "Draft", "WIP")
    namespace: NamespaceInfo | None  # Namespace information if the ASCCP belongs to a specific namespace
    library: LibraryInfo  # Library information where this ASCCP is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserInfo  # User information about the owner of the ASCCP
    created: WhoAndWhen  # Information about who created the ASCCP and when
    last_updated: WhoAndWhen  # Information about who last updated the ASCCP and when


# BCCP Response Models
class GetBccpResponse(BaseModel):
    """Response for get_bccp tool."""
    bccp_manifest_id: int  # Unique identifier for the BCCP manifest (release-specific version)
    bccp_id: int  # Unique identifier for the BCCP (base entity ID)
    bdt: BaseDtInfo  # Basic Data Type (BDT) information associated with this BCCP (required)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str  # Dictionary Entry Name (DEN) - following rule: bccp.property_term + ". " + dt.den.replace(". Type", "")
    property_term: str  # Property term as specified in CCTS v3, part of the component name
    representation_term: str  # Representation term as specified in CCTS v3, indicates the data format
    definition: str | None  # Definition or description of the BCCP
    definition_source: str | None  # URL indicating the source of the definition
    is_nillable: bool  # Whether the BCCP can have a nil/null value
    value_constraint: ValueConstraint | None  # Value constraint (default_value or fixed_value) for the BCCP
    is_deprecated: bool  # Whether the BCCP is deprecated and should not be used
    state: str | None  # Current state of the BCCP (e.g., "Published", "Draft", "WIP")
    namespace: NamespaceInfo | None  # Namespace information if the BCCP belongs to a specific namespace
    library: LibraryInfo  # Library information where this BCCP is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserInfo  # User information about the owner of the BCCP
    created: WhoAndWhen  # Information about who created the BCCP and when
    last_updated: WhoAndWhen  # Information about who last updated the BCCP and when


# Unified Core Component Response Models
class CoreComponentInfo(BaseModel):
    """Unified core component information object."""
    component_type: Literal["ACC", "ASCCP", "BCCP"]  # Type of component: "ACC" (Aggregation), "ASCCP" (Association Property), or "BCCP" (Basic Property)
    manifest_id: int  # Unique identifier for the component manifest (release-specific version)
    component_id: int  # Unique identifier for the component (base entity ID)
    guid: str  # Unique identifier within the release. 32-character hexadecimal identifier (lowercase, no hyphens)
    den: str | None  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    name: str | None  # Component name: object_class_term for ACC, property_term for ASCCP/BCCP
    definition: str | None  # Definition or description of the component
    definition_source: str | None  # URL indicating the source of the definition
    is_deprecated: bool  # Whether the component is deprecated and should not be used
    state: str | None  # Current state of the component (e.g., "Published", "Draft", "WIP", "QA", "Candidate", "Production")
    tag: str | None  # Tag name associated with the component (e.g., "BOD" for Business Object Document)
    namespace: NamespaceInfo | None  # Namespace information if the component belongs to a specific namespace
    library: LibraryInfo  # Library information where this component is stored
    release: ReleaseInfo  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserInfo  # User information about the owner of the component
    created: WhoAndWhen  # Information about who created the component and when
    last_updated: WhoAndWhen  # Information about who last updated the component and when


class GetCoreComponentPaginationResponse(PaginationResponse[CoreComponentInfo]):
    """Response for get_core_components tool."""
    pass

