"""
Service class for managing Data Type operations in connectCenter.

Data Types define the structure and constraints for data elements in business
information exchanges. This service provides comprehensive functionality for
querying, filtering, and retrieving Data Type data with support for pagination,
sorting, and date range filtering, including dependency-aware release queries.
"""

import logging

from fastapi import HTTPException
from sqlalchemy.orm import selectinload
from sqlmodel import select, func

from databases.models import Dt, DtManifest, DtScManifest, Release
from services.cache import cache
from services.common import create_user_info, validate_and_create_value_constraint
from services.models.common import Sort, PaginationParams, DateRangeParams, WhoAndWhen, PaginationResponse
from services.models.data_type import DtDto, DtScDto, DtSummary
from services.models.library import LibrarySummary
from services.models.log import LogInfo
from services.models.namespace import NamespaceSummary
from services.models.release import ReleaseSummary
from services.release import ReleaseService
from services.transaction import transaction, db_exec

# Configure logging
logger = logging.getLogger("score.service.data_type")


class DataTypeService:
    """
    Service class for managing Data Type operations.
    
    This service provides a comprehensive interface for working with Data Types,
    which define the structure and constraints for data elements in business
    information exchanges. The service handles querying, filtering, pagination,
    and retrieval of Data Type data with full relationship loading and dependency
    management.
    
    Key Features:
    - Query Data Types by release with automatic inclusion of dependent releases
    - Retrieve individual Data Types by ID or manifest ID
    - Get associated supplementary component manifests for Data Types
    - Support for pagination, sorting, and date range filtering
    - Automatic loading of related entities (namespace, creator, owner, release, etc.)
    - SQL injection protection through column whitelisting
    - Dependency-aware queries that include data types from dependent releases
    
    Main Operations:
    - get_data_types_by_release(): Retrieve paginated lists of Data Types filtered
      by release. Automatically includes Data Types from all dependent releases
      (recursively). Supports optional filters for DEN (Dictionary Entry Name) and
      representation_term, with date range filtering and custom sorting.
    
    - get_data_type_by_id(): Retrieve a single Data Type by its internal ID,
      including all related entities (namespace, creator, owner, release, log,
      based_dt_manifest).
    
    - get_data_type_by_manifest_id(): Retrieve a single Data Type by its manifest ID,
      which represents the type in a specific release context. Returns both the
      manifest and all associated supplementary component manifests.
    
    - get_supplementary_components_by_dt_manifest_id(): Retrieve all supplementary
      component manifests associated with a specific Data Type manifest, representing
      the additional components that extend the base data type.
    
    Filtering and Sorting:
    - Supports filtering by DEN (Dictionary Entry Name) using case-insensitive partial matching
      DEN format: '((qualifier) ? qualifier + "_ " : "") + data_type_term + ". Type"'
    - Filtering by representation_term (case-insensitive partial matching)
    - Date range filtering for creation and last update timestamps
    - Multi-column sorting with ascending/descending order
    - Default sorting by creation timestamp (newest first)
    - Column whitelist prevents SQL injection attacks
    
    Release Dependency Management:
    When querying by release, the service automatically includes Data Types from
    all dependent releases (recursively). This ensures that all relevant Data
    Types are available when working with a specific release, including those
    inherited from previous versions.
    
    Relationship Loading:
    All queries automatically load related entities using SQLAlchemy's selectinload:
    - Data Type: namespace, creator, owner, last_updater
    - Manifest: release (with library), log, based_dt_manifest (with dt and release)
    - Supplementary Components: associated dt_sc entities
    
    Transaction Management:
    All database operations are wrapped in read-only transactions to ensure
    data consistency and proper connection management.
    
    Example Usage:
        service = DataTypeService()
        
        # Get paginated data types for a release (includes dependent releases)
        page = service.get_data_types_by_release(
            release_id=1,
            den="Amount. Type",
            pagination=PaginationParams(offset=0, limit=10),
            sort_list=[Sort(column="den", direction="asc")]
        )
        
        # Get a specific data type by manifest ID with supplementary components
        manifest, sc_manifests = service.get_data_type_by_manifest_id(123)
        
        # Get supplementary components for a data type
        sc_manifests = service.get_supplementary_components_by_dt_manifest_id(123)
    """

    # Allowed columns for ordering to prevent SQL injection
    allowed_columns_for_order_by = [
        'den',
        'data_type_term',
        'qualifier',
        'representation_term',
        'six_digit_id',
        'definition',
        'creation_timestamp',
        'last_update_timestamp'
    ]

    def __init__(self):
        """
        Initialize the service.
        """
        pass

    @cache(key_prefix="data_type.get_data_types_by_release")
    @transaction(read_only=True)
    def get_data_types_by_release(
            self,
            release_id: int,
            den: str = None,
            representation_term: str = None,
            created_on_params: DateRangeParams = None,
            last_updated_on_params: DateRangeParams = None,
            pagination: PaginationParams = PaginationParams(offset=0, limit=10),
            sort_list: list[Sort] = None
    ) -> PaginationResponse[DtDto]:
        """
        Get data types associated with a specific release and its dependent releases.
        
        Args:
            release_id: ID of the release to filter by
            pagination: Pagination parameters
            sort_list: List of sort specifications
            den: Filter by Dictionary Entry Name (DEN) using partial match (case-insensitive).
                DEN format: '((qualifier) ? qualifier + "_ " : "") + data_type_term + ". Type"'
            representation_term: Filter by representation term (partial match)
            created_on_params: Date range filter for creation timestamp
            last_updated_on_params: Date range filter for last update timestamp
        
        Returns:
            PaginationResponse: Paginated response containing data type manifests with supplementary components included
        """
        # Set default pagination if not provided
        if pagination is None:
            pagination = PaginationParams(offset=0, limit=10)

        # Get dependent releases
        release_service = ReleaseService()
        dependent_release_ids = release_service.get_dependent_releases(release_id)

        # Include the original release ID and all dependent release IDs
        all_release_ids = [release_id] + dependent_release_ids

        # Build the base query to get manifests with loaded relationships
        query = select(DtManifest).options(
            selectinload(DtManifest.dt).selectinload(Dt.namespace),
            selectinload(DtManifest.dt).selectinload(Dt.creator),
            selectinload(DtManifest.dt).selectinload(Dt.owner),
            selectinload(DtManifest.dt).selectinload(Dt.last_updater),
            selectinload(DtManifest.release).selectinload(Release.library),
            selectinload(DtManifest.log),
            selectinload(DtManifest.based_dt_manifest).selectinload(DtManifest.dt).selectinload(Dt.namespace),
            selectinload(DtManifest.based_dt_manifest).selectinload(DtManifest.release).selectinload(Release.library)
        ).join(Dt).where(DtManifest.release_id.in_(all_release_ids)).distinct()

        # Apply filters
        query = self._apply_filters(query, den, representation_term, created_on_params, last_updated_on_params)

        # Get total count
        count_query = select(func.count()).select_from(query.subquery())
        total_count = db_exec(count_query).one()

        # Apply sorting
        query = self._apply_sorting(query, sort_list)

        # Apply pagination
        query = query.offset(pagination.offset).limit(pagination.limit)

        # Execute query
        manifests = db_exec(query).all()

        # Create Page object
        return PaginationResponse(
            total_items=total_count,
            offset=pagination.offset,
            limit=pagination.limit,
            items=[self._create_data_type_result(dt_manifest) for dt_manifest in manifests]
        )

    def _apply_filters(self, query, den: str = None, representation_term: str = None,
                       created_on_params: DateRangeParams = None,
                       last_updated_on_params: DateRangeParams = None):
        """
        Apply filters to a query for data types.
        
        Args:
            query: The base query to apply filters to
            den: Filter by Dictionary Entry Name (DEN) using partial match
            representation_term: Filter by representation term (partial match)
            created_on_params: Date range filter for creation timestamp
            last_updated_on_params: Date range filter for last update timestamp
            
        Returns:
            Query with filters applied
        """
        if den:
            query = query.where(DtManifest.den.ilike(f"%{den}%"))

        if representation_term:
            query = query.where(Dt.representation_term.ilike(f"%{representation_term}%"))

        if created_on_params:
            if created_on_params.before:
                query = query.where(Dt.creation_timestamp >= created_on_params.before)
            if created_on_params.after:
                query = query.where(Dt.creation_timestamp <= created_on_params.after)

        if last_updated_on_params:
            if last_updated_on_params.before:
                query = query.where(Dt.last_update_timestamp >= last_updated_on_params.before)
            if last_updated_on_params.after:
                query = query.where(Dt.last_update_timestamp <= last_updated_on_params.after)

        return query

    def _apply_sorting(self, query, sort_list: list[Sort] = None):
        """
        Apply sorting to a query for data types.
        
        Args:
            query: The query to apply sorting to
            sort_list: List of Sort objects for ordering
            
        Returns:
            Query with sorting applied
        """
        if sort_list:
            # Validate sort columns against allowed list
            for sort in sort_list:
                if sort.column not in self.allowed_columns_for_order_by:
                    raise HTTPException(
                        status_code=400,
                        detail=f"Invalid sort column: '{sort.column}'. Allowed columns: {', '.join(self.allowed_columns_for_order_by)}"
                    )

            # Apply sorting
            for sort in sort_list:
                if sort.column == 'den':
                    column = DtManifest.den
                else:
                    column = getattr(Dt, sort.column)
                if sort.direction == 'desc':
                    query = query.order_by(column.desc())
                else:
                    query = query.order_by(column.asc())
        else:
            query = query.order_by(Dt.creation_timestamp.desc())

        return query

    @cache(key_prefix="data_type.get_data_type_by_manifest_id")
    @transaction(read_only=True)
    def get_data_type_by_manifest_id(self, dt_manifest_id: int) -> DtDto:
        """
        Get a data type by its manifest ID.
        
        Args:
            dt_manifest_id: ID of the data type manifest to retrieve
        
        Returns:
            DtManifest: The data type manifest with loaded relationships if found
        
        Raises:
            HTTPException: If data type manifest not found
        """
        # Get the manifest with all relationships loaded
        manifest_query = select(DtManifest).options(
            selectinload(DtManifest.dt).selectinload(Dt.namespace),
            selectinload(DtManifest.dt).selectinload(Dt.creator),
            selectinload(DtManifest.dt).selectinload(Dt.owner),
            selectinload(DtManifest.dt).selectinload(Dt.last_updater),
            selectinload(DtManifest.release).selectinload(Release.library),
            selectinload(DtManifest.log),
            selectinload(DtManifest.based_dt_manifest).selectinload(DtManifest.dt).selectinload(Dt.namespace),
            selectinload(DtManifest.based_dt_manifest).selectinload(DtManifest.release).selectinload(Release.library)
        ).where(
            DtManifest.dt_manifest_id == dt_manifest_id
        )
        manifest = db_exec(manifest_query).first()
        if not manifest:
            raise HTTPException(
                status_code=404,
                detail=f"Data type manifest with ID {dt_manifest_id} not found"
            )

        return self._create_data_type_result(manifest)

    @cache(key_prefix="data_type.get_supplementary_components_by_dt_manifest_id")
    @transaction(read_only=True)
    def get_supplementary_components_by_dt_manifest_id(self, dt_manifest_id: int) -> list["DtScManifest"]:
        """
        Get supplementary components for a specific data type manifest.
        
        Args:
            dt_manifest_id: The data type manifest ID
            
        Returns:
            list[DtScManifest]: List of supplementary component manifests for the data type
        """
        query = select(DtScManifest).options(
            selectinload(DtScManifest.dt_sc)
        ).where(DtScManifest.owner_dt_manifest_id == dt_manifest_id)
        sc_manifests = db_exec(query).all()
        return list(sc_manifests)

    def _create_data_type_result(self, manifest) -> DtDto:
        """
        Create a data type result from a DtManifest model instance.

        Args:
            manifest: DtManifest model instance with dt relationship
            data_type_service: DataTypeService instance for retrieving related data

        Returns:
            GetDataTypeResponse: Formatted data type result
        """
        data_type = manifest.dt

        # Get supplementary components using the separate service function
        try:
            sc_manifests = self.get_supplementary_components_by_dt_manifest_id(manifest.dt_manifest_id)
        except Exception as e:
            logger.warning(f"Failed to retrieve supplementary components for DtManifest {manifest.dt_manifest_id}", e)
            sc_manifests = []  # Continue without supplementary components rather than failing completely

        # Create namespace info if available
        namespace_info = None
        if data_type.namespace:
            namespace_info = NamespaceSummary(
                namespace_id=data_type.namespace.namespace_id,
                prefix=data_type.namespace.prefix,
                uri=data_type.namespace.uri
            )

        # Create library info from release
        library_info = LibrarySummary(
            library_id=manifest.release.library_id,
            name=manifest.release.library.name
        )

        # Create release info from manifest
        # Since release_id is required and release relationship is loaded, release should always be available
        release_info = ReleaseSummary(
            release_id=manifest.release_id,
            release_num=manifest.release.release_num,
            state=manifest.release.state
        )

        # Create log info from manifest
        log_info = None
        if manifest.log:
            log_info = LogInfo(
                log_id=manifest.log.log_id,
                revision_num=manifest.log.revision_num,
                revision_tracking_num=manifest.log.revision_tracking_num
            )

        # Create supplementary components info from sc manifests
        supplementary_components_info = []
        for sc_manifest in sc_manifests:
            value_constraint = validate_and_create_value_constraint(
                default_value=sc_manifest.dt_sc.default_value,
                fixed_value=sc_manifest.dt_sc.fixed_value
            )
            supplementary_components_info.append(DtScDto(
                dt_sc_manifest_id=sc_manifest.dt_sc_manifest_id,
                dt_sc_id=sc_manifest.dt_sc_id,
                guid=sc_manifest.dt_sc.guid,
                object_class_term=sc_manifest.dt_sc.object_class_term,
                property_term=sc_manifest.dt_sc.property_term,
                representation_term=sc_manifest.dt_sc.representation_term,
                definition=sc_manifest.dt_sc.definition,
                definition_source=sc_manifest.dt_sc.definition_source,
                cardinality_min=sc_manifest.dt_sc.cardinality_min,
                cardinality_max=sc_manifest.dt_sc.cardinality_max,
                value_constraint=value_constraint,
                is_deprecated=sc_manifest.dt_sc.is_deprecated
            ))

        # Create base data type info if available
        base_dt_info = None
        if manifest.based_dt_manifest_id:
            based_dt = self.get_data_type_by_manifest_id(manifest.based_dt_manifest_id)
            base_dt_info = self.create_dt_summary(based_dt)

        return DtDto(
            dt_manifest_id=manifest.dt_manifest_id,
            dt_id=data_type.dt_id,
            base_dt=base_dt_info,
            guid=data_type.guid,
            den=manifest.den,
            data_type_term=data_type.data_type_term,
            qualifier=data_type.qualifier,
            representation_term=data_type.representation_term,
            six_digit_id=data_type.six_digit_id,
            definition=data_type.definition,
            definition_source=data_type.definition_source,
            content_component_definition=data_type.content_component_definition,
            namespace=namespace_info,
            library=library_info,
            release=release_info,
            log=log_info,
            commonly_used=data_type.commonly_used,
            is_deprecated=data_type.is_deprecated,
            state=data_type.state,
            supplementary_components=supplementary_components_info,
            owner=create_user_info(data_type.owner),
            created=WhoAndWhen(
                who=create_user_info(data_type.creator),
                when=data_type.creation_timestamp
            ),
            last_updated=WhoAndWhen(
                who=create_user_info(data_type.last_updater),
                when=data_type.last_update_timestamp
            )
        )

    def create_dt_summary(self, dt: DtDto) -> DtSummary | None:
        if not dt:
            return None

        return DtSummary(
            dt_manifest_id=dt.dt_manifest_id,
            dt_id=dt.dt_id,
            based_dt_manifest_id=dt.base_dt.dt_manifest_id if dt.base_dt else None,
            guid=dt.guid,
            den=dt.den,
            data_type_term=dt.data_type_term,
            qualifier=dt.qualifier,
            representation_term=dt.representation_term,
            six_digit_id=dt.six_digit_id,
            definition=dt.definition,
            definition_source=dt.definition_source,
            content_component_definition=dt.content_component_definition,
            is_deprecated=dt.is_deprecated,
            namespace=dt.namespace,
            library=dt.library,
            release=dt.release
        )
