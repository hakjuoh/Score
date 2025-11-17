"""Models for Core Component tools."""
from __future__ import annotations

from typing import List, Union

from pydantic import BaseModel

from services.models.common import UserSummary, WhoAndWhen, PaginationResponse
from services.models.core_component import (
    CoreComponentListEntry,
    BaseAccInfo,
    AsccRelationshipInfo,
    BccRelationshipInfo,
    ValueConstraint
)
from services.models.data_type import DtSummary
from services.models.library import LibrarySummary
from services.models.log import LogInfo
from services.models.namespace import NamespaceSummary
from services.models.release import ReleaseSummary


# ACC Response Models
class GetAccResponse(BaseModel):
    """Response for get_acc tool."""
    acc_manifest_id: int  # Unique identifier for the ACC manifest (release-specific version)
    acc_id: int  # Unique identifier for the ACC (base entity ID)
    base_acc: BaseAccInfo | None  # Base ACC information if this ACC is derived from another
    relationships: List[Union[
        AsccRelationshipInfo, BccRelationshipInfo]]  # List of related components (ASCCs and BCCs) contained in the ACC
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
    namespace: NamespaceSummary | None  # Namespace information if the ACC belongs to a specific namespace
    library: LibrarySummary  # Library information where this ACC is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserSummary  # User information about the owner of the ACC
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
    namespace: NamespaceSummary | None  # Namespace information if the ASCCP belongs to a specific namespace
    library: LibrarySummary  # Library information where this ASCCP is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserSummary  # User information about the owner of the ASCCP
    created: WhoAndWhen  # Information about who created the ASCCP and when
    last_updated: WhoAndWhen  # Information about who last updated the ASCCP and when


# BCCP Response Models
class GetBccpResponse(BaseModel):
    """Response for get_bccp tool."""
    bccp_manifest_id: int  # Unique identifier for the BCCP manifest (release-specific version)
    bccp_id: int  # Unique identifier for the BCCP (base entity ID)
    bdt: DtSummary  # Basic Data Type (BDT) information associated with this BCCP (required)
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
    namespace: NamespaceSummary | None  # Namespace information if the BCCP belongs to a specific namespace
    library: LibrarySummary  # Library information where this BCCP is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    log: LogInfo | None  # Log information tracking revision history (if available)
    owner: UserSummary  # User information about the owner of the BCCP
    created: WhoAndWhen  # Information about who created the BCCP and when
    last_updated: WhoAndWhen  # Information about who last updated the BCCP and when


class GetCoreComponentPaginationResponse(PaginationResponse[CoreComponentListEntry]):
    """Response for get_core_components tool."""
    pass
