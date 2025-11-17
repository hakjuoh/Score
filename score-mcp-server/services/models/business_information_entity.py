"""Generic models for Business Information Entity domain."""
from __future__ import annotations

from typing import Literal, List, Union

from pydantic import BaseModel, computed_field, model_validator

from services.models.biz_ctx import BizCtxSummary
from services.models.common import UserSummary, WhoAndWhen, ValueConstraint
from services.models.core_component import AccInfo, AsccInfo, AsccpInfo, BccInfo, BccpInfo
from services.models.data_type import DtScDto
from services.models.library import LibrarySummary
from services.models.release import ReleaseSummary


class TopLevelAsbiepListEntry(BaseModel):
    """Response for get_top_level_asbiep tool."""
    top_level_asbiep_id: int  # Unique identifier for the top-level ASBIEP
    asbiep_id: int  # Unique identifier for the ASBIEP (base entity ID)
    guid: str  # Globally unique identifier for the ASBIEP
    den: str  # Dictionary Entry Name (DEN) - the standardized name as defined by CCTS v3
    property_term: str  # Property term from the underlying ASCCP
    display_name: str | None  # Display name intended for user interface presentation
    version: str | None  # Version string of the top-level ASBIEP (e.g., "1.0", "2.1")
    status: str | None  # Status of the top-level ASBIEP (e.g., "Production", "Draft")
    biz_term: str | None  # Business term that represents this top-level ASBIEP in business language
    remark: str | None  # Additional remarks or notes about the top-level ASBIEP
    business_contexts: list[BizCtxSummary]  # List of business contexts associated with this top-level ASBIEP
    state: str  # Current state of the top-level ASBIEP (e.g., "WIP", "QA", "Production", "Published")
    is_deprecated: bool  # Whether the top-level ASBIEP is deprecated and should not be used
    deprecated_reason: str | None  # Reason why the top-level ASBIEP was deprecated
    deprecated_remark: str | None  # Additional remarks about the deprecation
    owner: UserSummary  # User information about the owner of the top-level ASBIEP
    created: WhoAndWhen  # Information about who created the top-level ASBIEP and when
    last_updated: WhoAndWhen  # Information about who last updated the top-level ASBIEP and when


class TopLevelAsbiepInfo(BaseModel):
    """Top-Level ASBIEP information object."""
    top_level_asbiep_id: int  # Unique identifier for the top-level ASBIEP
    library: LibrarySummary  # Library information where this top-level ASBIEP is stored
    release: ReleaseSummary  # Release information indicating which release this version belongs to
    version: str | None  # Version string of the top-level ASBIEP (e.g., "1.0", "2.1")
    status: str | None  # Status of the top-level ASBIEP (e.g., "Production", "Draft")
    state: str  # Current state of the top-level ASBIEP (e.g., "WIP", "QA", "Production", "Published")
    is_deprecated: bool  # Whether the top-level ASBIEP is deprecated and should not be used
    deprecated_reason: str | None  # Reason why the top-level ASBIEP was deprecated
    deprecated_remark: str | None  # Additional remarks about the deprecation
    owner: UserSummary  # User information about the owner of the top-level ASBIEP


class AbieRelationshipInfo(BaseModel):
    """Base ABIE relationship information object.
    
    The is_used property indicates profiling status:
    - is_used=True: Relationship is profiled for practical use (asbie_id/bbie_id is presented)
    - is_used=False: Relationship is available for profiling but not currently used
    """
    component_type: Literal["ASBIE", "BBIE"]  # Type of relationship: "ASBIE" (Association) or "BBIE" (Basic)
    is_used: bool  # Whether this component is currently being used (profiled) in the BIE (True means asbie_id/bbie_id exists)
    path: str  # Hierarchical path string indicating the position of this component within the BIE structure
    hash_path: str  # Hashed version of the path for efficient lookups
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    is_nillable: bool  # Whether the component can have a nil/null value
    remark: str | None  # Additional remarks or notes about the component

    @computed_field
    @property
    def cardinality_display(self) -> str:
        """Display cardinality with 'unbounded' for -1 values."""
        if self.cardinality_max == -1:
            return f"{self.cardinality_min}..unbounded"
        else:
            return f"{self.cardinality_min}..{self.cardinality_max}"


class AsbieRelationshipInfo(AbieRelationshipInfo):
    """ASBIE relationship information object.
    
    When is_used=True, asbie_id will be created for practical use.
    When is_used=False, the relationship is available for profiling.
    """
    component_type: Literal["ASBIE"] = "ASBIE"  # Type of relationship, always "ASBIE" for this class
    asbie_id: int | None  # Unique identifier for the ASBIE (base entity ID, None if is_used=False)
    guid: str | None  # Globally unique identifier for the ASBIE (if available)
    based_ascc: AsccInfo  # Information about the ASCC that this ASBIE component is based on
    to_asbiep_id: int | None  # Unique identifier for the target ASBIEP that this ASBIE connects to (if available)

    @property
    def based_ascc_manifest_id(self) -> int:
        """Get the ASCC manifest ID from the based ASCC."""
        return self.based_ascc.ascc_manifest_id

    @property
    def based_manifest_id(self) -> int:
        return self.based_ascc.ascc_manifest_id


class Facet(BaseModel):
    """Facet restriction information for string values."""
    facet_min_length: int | None  # Minimum length constraint for string values (facet restriction)
    facet_max_length: int | None  # Maximum length constraint for string values (facet restriction)
    facet_pattern: str | None  # Pattern constraint (regular expression) for string values (facet restriction)


class PrimitiveRestriction(BaseModel):
    """Primitive restriction information for BBIE.
    
    Validation rules:
    - Should NOT be None (enforced at usage sites)
    - Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set (not multiple, not none)
    """
    xbtManifestId: int | None  # XBT (eXtended Built-in Type) manifest ID
    codeListManifestId: int | None  # Code list manifest ID
    agencyIdListManifestId: int | None  # Agency ID list manifest ID

    @model_validator(mode='after')
    def validate_primitive_restriction(self):
        """Validate that exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId is set."""
        has_xbt = self.xbtManifestId is not None
        has_code_list = self.codeListManifestId is not None
        has_agency_id_list = self.agencyIdListManifestId is not None

        count = sum([has_xbt, has_code_list, has_agency_id_list])

        if count == 0:
            raise ValueError(
                "PrimitiveRestriction: Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set. "
                "All cannot be None."
            )
        if count > 1:
            raise ValueError(
                "PrimitiveRestriction: Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set. "
                f"Found {count} values set: "
                f"{'xbtManifestId' if has_xbt else ''} "
                f"{'codeListManifestId' if has_code_list else ''} "
                f"{'agencyIdListManifestId' if has_agency_id_list else ''}".strip()
            )

        return self


class BbieRelationshipInfo(AbieRelationshipInfo):
    """BBIE relationship information object.
    
    When is_used=True, bbie_id will be created for practical use.
    When is_used=False, the relationship is available for profiling.
    """
    component_type: Literal["BBIE"] = "BBIE"  # Type of relationship, always "BBIE" for this class
    bbie_id: int | None  # Unique identifier for the BBIE (base entity ID, None if is_used=False)
    guid: str | None  # Globally unique identifier for the BBIE (if available)
    based_bcc: BccInfo  # Information about the BCC that this BBIE component is based on
    primitiveRestriction: PrimitiveRestriction  # Primitive restriction information (xbtManifestId, codeListManifestId, agencyIdListManifestId). Required field that must not be None. Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set (not multiple, not none).
    valueConstraint: ValueConstraint | None  # Value constraint information (default_value, fixed_value)
    facet: Facet | None  # Facet restriction information (min_length, max_length, pattern)
    to_bbiep_id: int | None  # Unique identifier for the target BBIEP that this BBIE connects to (if available)

    @property
    def based_bcc_manifest_id(self) -> int:
        """Get the BCC manifest ID from the based BCC."""
        return self.based_bcc.bcc_manifest_id

    @property
    def based_manifest_id(self) -> int:
        return self.based_bcc.bcc_manifest_id


class BbieScInfo(BaseModel):
    """BBIE SC (Business Information Entity Supplementary Component) information object."""
    bbie_sc_id: int | None  # Unique identifier for the BBIE SC (base entity ID, None if not yet created)
    guid: str | None  # Globally unique identifier for the BBIE SC (if available)
    based_dt_sc: DtScDto  # Information about the data type supplementary component that this BBIE SC is based on
    path: str  # Hierarchical path string indicating the position of this BBIE SC within the BIE structure
    hash_path: str  # Hashed version of the path for efficient lookups
    definition: str | None  # Definition or description of the BBIE SC
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    primitiveRestriction: PrimitiveRestriction  # Primitive restriction information (xbtManifestId, codeListManifestId, agencyIdListManifestId). Required field that must not be None. Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set (not multiple, not none).
    valueConstraint: ValueConstraint | None  # Value constraint information (default_value, fixed_value)
    facet: Facet | None  # Facet restriction information (min_length, max_length, pattern)
    owner_top_level_asbiep: TopLevelAsbiepInfo  # Information about the top-level ASBIEP that owns this BBIE SC


class BbiepInfo(BaseModel):
    """BBIEP (Basic Business Information Entity Property) information object."""
    bbiep_id: int | None  # Unique identifier for the BBIEP (base entity ID, None if not yet created)
    guid: str | None  # Globally unique identifier for the BBIEP (if available)
    based_bccp: BccpInfo  # Information about the BCCP that this BBIEP is based on
    path: str  # Hierarchical path string indicating the position of this BBIEP within the BIE structure
    hash_path: str  # Hashed version of the path for efficient lookups
    definition: str | None  # Definition or description of the BBIEP
    remark: str | None  # Additional remarks or notes about the BBIEP
    biz_term: str | None  # Business term that represents this BBIEP in business language
    display_name: str | None  # Display name intended for user interface presentation
    supplementary_components: list[BbieScInfo]  # List of supplementary components associated with this BBIEP
    owner_top_level_asbiep: TopLevelAsbiepInfo  # Information about the top-level ASBIEP that owns this BBIEP


class BbieInfo(BaseModel):
    """BBIE (Basic Business Information Entity) information object."""
    bbie_id: int  # Unique identifier for the BBIE (base entity ID)
    guid: str  # Globally unique identifier for the BBIE
    based_bcc: BccInfo  # Information about the BCC that this BBIE is based on
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    is_nillable: bool  # Whether the BBIE can have a nil/null value
    remark: str | None  # Additional remarks or notes about the BBIE
    primitiveRestriction: PrimitiveRestriction  # Primitive restriction information (xbtManifestId, codeListManifestId, agencyIdListManifestId). Required field that must not be None. Exactly one of xbtManifestId, codeListManifestId, or agencyIdListManifestId must be set (not multiple, not none).
    valueConstraint: ValueConstraint | None  # Value constraint information (default_value, fixed_value)
    facet: Facet | None  # Facet restriction information (min_length, max_length, pattern)
    bbiep: BbiepInfo  # Information about the BBIEP associated with this BBIE
    owner_top_level_asbiep: TopLevelAsbiepInfo  # Information about the top-level ASBIEP that owns this BBIE


class AsbieInfo(BaseModel):
    """ASBIE (Association Business Information Entity) information object."""
    asbie_id: int  # Unique identifier for the ASBIE (base entity ID)
    guid: str  # Globally unique identifier for the ASBIE
    based_ascc: AsccInfo  # Information about the ASCC that this ASBIE is based on
    cardinality_min: int  # Minimum cardinality (minimum number of occurrences required, typically 0 or 1)
    cardinality_max: int  # Maximum cardinality (maximum number of occurrences allowed, -1 means unbounded)
    is_nillable: bool  # Whether the ASBIE can have a nil/null value
    remark: str | None  # Additional remarks or notes about the ASBIE
    asbiep: "AsbiepInfo"  # Information about the ASBIEP associated with this ASBIE
    owner_top_level_asbiep: TopLevelAsbiepInfo  # Information about the top-level ASBIEP that owns this ASBIE


class AsbiepInfo(BaseModel):
    """ASBIEP (Association Business Information Entity Property) information object."""
    asbiep_id: int | None  # Unique identifier for the ASBIEP (base entity ID, None if not yet created)
    owner_top_level_asbiep: TopLevelAsbiepInfo | None  # Information about the top-level ASBIEP that owns this ASBIEP
    based_asccp_manifest: AsccpInfo  # Information about the ASCCP that this ASBIEP is based on
    path: str | None  # Hierarchical path string indicating the position of this ASBIEP within the BIE structure
    hash_path: str | None  # Hashed version of the path for efficient lookups
    role_of_abie: "AbieInfo"  # Information about the ABIE that this ASBIEP plays the role of
    definition: str | None  # Definition or description of the ASBIEP
    remark: str | None  # Additional remarks or notes about the ASBIEP
    biz_term: str | None  # Business term that represents this ASBIEP in business language
    display_name: str | None  # Display name intended for user interface presentation
    created: WhoAndWhen | None  # Information about who created the ASBIEP and when (if available)
    last_updated: WhoAndWhen | None  # Information about who last updated the ASBIEP and when (if available)


class AbieInfo(BaseModel):
    """ABIE (Aggregation Business Information Entity) information object."""
    abie_id: int | None  # Unique identifier for the ABIE (base entity ID, None if not yet created)
    guid: str | None  # Globally unique identifier for the ABIE (if available)
    based_acc_manifest: AccInfo  # Information about the ACC that this ABIE is based on
    definition: str | None  # Definition or description of the ABIE
    remark: str | None  # Additional remarks or notes about the ABIE
    relationships: List[Union[
        AsbieRelationshipInfo, BbieRelationshipInfo]]  # List of relationships (ASBIEs and BBIEs) contained in the ABIE
    created: WhoAndWhen | None  # Information about who created the ABIE and when (if available)
    last_updated: WhoAndWhen | None  # Information about who last updated the ABIE and when (if available)
