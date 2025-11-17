"""
Service class for managing Code List operations in connectCenter.

Code Lists are standardized lists of values used to represent enumerated data
in business information exchanges. This service provides comprehensive functionality
for querying, filtering, and retrieving Code List data with support for pagination,
sorting, and date range filtering.
"""

import logging

from fastapi import HTTPException
from sqlalchemy.orm import selectinload
from sqlmodel import select, func

from databases.models import CodeList, CodeListManifest, CodeListValueManifest, Release
from services.cache import cache
from services.common import create_user_info
from services.models import NamespaceSummary, LibrarySummary, ReleaseSummary, LogInfo
from services.models.code_list import CodeListDto, CodeListValueDto
from services.models.common import Sort, PaginationParams, DateRangeParams, WhoAndWhen, PaginationResponse
from services.transaction import transaction, db_exec

# Configure logging
logger = logging.getLogger("score.service.code_list")


class CodeListService:
    """
    Service class for managing Code List operations.
    
    This service provides a comprehensive interface for working with Code Lists,
    which are standardized lists of values used to represent enumerated data in
    business information exchanges. The service handles querying, filtering, pagination,
    and retrieval of Code List data with full relationship loading.
    
    Key Features:
    - Query Code Lists by release with advanced filtering
    - Retrieve individual Code Lists by ID or manifest ID
    - Get associated value manifests for Code Lists
    - Support for pagination, sorting, and date range filtering
    - Automatic loading of related entities (namespace, creator, owner, release, etc.)
    - SQL injection protection through column whitelisting
    
    Main Operations:
    - get_code_lists_by_release(): Retrieve paginated lists of Code Lists filtered
      by release with optional filters for name, list_id, version_id, and date ranges.
      Supports custom sorting and pagination.
    
    - get_code_list_by_id(): Retrieve a single Code List by its internal ID, including
      all related entities (namespace, creator, owner, values, release, log).
    
    - get_code_list_by_manifest_id(): Retrieve a single Code List by its manifest ID,
      which represents the list in a specific release context. Includes all relationships
      and value manifests.
    
    - get_code_list_value_manifests_by_manifest_id(): Retrieve all value manifests
      associated with a specific Code List manifest, representing the individual
      code values/entries in the list.
    
    Filtering and Sorting:
    - Supports filtering by name, list_id, and version_id (case-insensitive partial matching)
    - Date range filtering for creation and last update timestamps
    - Multi-column sorting with ascending/descending order
    - Default sorting by creation timestamp (newest first)
    - Column whitelist prevents SQL injection attacks
    
    Relationship Loading:
    All queries automatically load related entities using SQLAlchemy's selectinload:
    - Code List: namespace, creator, owner, last_updater, code_list_values
    - Manifest: release (with library), log, code_list_value_manifests
    - Value Manifests: associated code_list_value entities
    
    Transaction Management:
    All database operations are wrapped in read-only transactions to ensure
    data consistency and proper connection management.
    
    Example Usage:
        service = CodeListService()
        
        # Get paginated lists for a release with filters
        page = service.get_code_lists_by_release(
            release_id=1,
            name="ISO",
            pagination=PaginationParams(offset=0, limit=10),
            sort_list=[Sort(column="name", direction="asc")]
        )
        
        # Get a specific list by manifest ID
        manifest = service.get_code_list_by_manifest_id(123)
        
        # Get value manifests for a list
        values = service.get_code_list_value_manifests_by_manifest_id(123)
    """

    # Allowed columns for ordering to prevent SQL injection
    allowed_columns_for_order_by = [
        'name',
        'list_id',
        'version_id',
        'definition',
        'creation_timestamp',
        'last_update_timestamp'
    ]

    def __init__(self):
        """
        Initialize the service.
        """
        pass

    @cache(key_prefix="code_list.get_code_lists_by_release")
    @transaction(read_only=True)
    def get_code_lists_by_release(
            self,
            release_id: int,
            name: str = None,
            list_id: str = None,
            version_id: str = None,
            created_on_params: DateRangeParams = None,
            last_updated_on_params: DateRangeParams = None,
            pagination: PaginationParams = PaginationParams(offset=0, limit=10),
            sort_list: list[Sort] = None
    ) -> PaginationResponse[CodeListDto]:
        """
        Get code lists associated with a specific release.
        
        Args:
            release_id: ID of the release to filter by
            pagination: Pagination parameters
            sort_list: List of sort specifications
            name: Filter by code list name (partial match)
            list_id: Filter by list ID (partial match)
            version_id: Filter by version ID (partial match)
            created_on_params: Date range filter for creation timestamp
            last_updated_on_params: Date range filter for last update timestamp
        
        Returns:
            PaginationResponse: Paginated response containing code list manifests with value manifests included
        """
        # Set default pagination if not provided
        if pagination is None:
            pagination = PaginationParams(offset=0, limit=10)

        # Build the base query to get manifests with loaded relationships
        query = select(CodeListManifest).options(
            selectinload(CodeListManifest.code_list).selectinload(CodeList.namespace),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.creator),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.owner),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.last_updater),
            selectinload(CodeListManifest.release).selectinload(Release.library),
            selectinload(CodeListManifest.log)
        ).join(CodeList).where(CodeListManifest.release_id == release_id)

        # Apply filters
        query = self._apply_filters(query, name, list_id, version_id, created_on_params, last_updated_on_params)

        # Get total count
        count_query = select(func.count()).select_from(query.subquery())
        total_count = db_exec(count_query).one()

        # Apply sorting
        query = self._apply_sorting(query, sort_list)

        # Apply pagination
        query = query.offset(pagination.offset).limit(pagination.limit)

        # Execute query
        code_list_manifests = db_exec(query).all()

        # Create Page object
        return PaginationResponse(
            total_items=total_count,
            offset=pagination.offset,
            limit=pagination.limit,
            items=[self._create_code_list_result(code_list_manifest) for code_list_manifest in code_list_manifests]
        )

    def _apply_filters(self, query, name: str = None, list_id: str = None,
                       version_id: str = None, created_on_params: DateRangeParams = None,
                       last_updated_on_params: DateRangeParams = None):
        """
        Apply filters to a query for code lists.
        
        Args:
            query: The base query to apply filters to
            name: Filter by code list name (partial match)
            list_id: Filter by list ID (partial match)
            version_id: Filter by version ID (partial match)
            created_on_params: Date range filter for creation timestamp
            last_updated_on_params: Date range filter for last update timestamp
            
        Returns:
            Query with filters applied
        """
        if name:
            query = query.where(CodeList.name.ilike(f"%{name}%"))

        if list_id:
            query = query.where(CodeList.list_id.ilike(f"%{list_id}%"))

        if version_id:
            query = query.where(CodeList.version_id.ilike(f"%{version_id}%"))

        if created_on_params:
            if created_on_params.before:
                query = query.where(CodeList.creation_timestamp >= created_on_params.before)
            if created_on_params.after:
                query = query.where(CodeList.creation_timestamp <= created_on_params.after)

        if last_updated_on_params:
            if last_updated_on_params.before:
                query = query.where(CodeList.last_update_timestamp >= last_updated_on_params.before)
            if last_updated_on_params.after:
                query = query.where(CodeList.last_update_timestamp <= last_updated_on_params.after)

        return query

    def _apply_sorting(self, query, sort_list: list[Sort] = None):
        """
        Apply sorting to a query for code lists.
        
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
                column = getattr(CodeList, sort.column)
                if sort.direction == 'desc':
                    query = query.order_by(column.desc())
                else:
                    query = query.order_by(column.asc())
        else:
            query = query.order_by(CodeList.creation_timestamp.desc())

        return query

    @cache(key_prefix="code_list.get_code_list_by_manifest_id")
    @transaction(read_only=True)
    def get_code_list_by_manifest_id(self, code_list_manifest_id: int) -> CodeListDto:
        """
        Get a code list by its manifest ID.
        
        Args:
            code_list_manifest_id: ID of the code list manifest to retrieve
        
        Returns:
            CodeListManifest: The code list manifest with loaded relationships if found
        
        Raises:
            HTTPException: If code list manifest not found
        """
        # Get the manifest with all relationships loaded
        manifest_query = select(CodeListManifest).options(
            selectinload(CodeListManifest.code_list).selectinload(CodeList.namespace),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.creator),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.owner),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.last_updater),
            selectinload(CodeListManifest.code_list).selectinload(CodeList.code_list_values),
            selectinload(CodeListManifest.release).selectinload(Release.library),
            selectinload(CodeListManifest.log),
            selectinload(CodeListManifest.code_list_value_manifests).selectinload(CodeListValueManifest.code_list_value)
        ).where(
            CodeListManifest.code_list_manifest_id == code_list_manifest_id
        )
        manifest = db_exec(manifest_query).first()
        if not manifest:
            raise HTTPException(
                status_code=404,
                detail=f"Code list manifest with ID {code_list_manifest_id} not found"
            )

        return self._create_code_list_result(manifest)

    @cache(key_prefix="code_list.get_code_list_value_manifests_by_manifest_id")
    @transaction(read_only=True)
    def get_code_list_value_manifests_by_manifest_id(self, code_list_manifest_id: int) -> list["CodeListValueManifest"]:
        """
        Get code list value manifests for a specific code list manifest.
        
        Args:
            code_list_manifest_id: The code list manifest ID
            
        Returns:
            list[CodeListValueManifest]: List of value manifests for the code list
        """
        query = select(CodeListValueManifest).options(
            selectinload(CodeListValueManifest.code_list_value)
        ).where(CodeListValueManifest.code_list_manifest_id == code_list_manifest_id)
        value_manifests = db_exec(query).all()
        return list(value_manifests)

    def _create_code_list_result(self, code_list_manifest) -> CodeListDto:
        """
        Create a code list result from a CodeListManifest model instance.

        Args:
            code_list_manifest: CodeListManifest model instance with code_list relationship

        Returns:
            CodeListDto: Formatted code list result
        """
        code_list = code_list_manifest.code_list

        # Get value manifests using the separate service function
        try:
            value_manifests = self.get_code_list_value_manifests_by_manifest_id(
                code_list_manifest.code_list_manifest_id)
        except Exception as e:
            logger.warning(f"Failed to retrieve value manifests for CodeListManifest {code_list_manifest.code_list_manifest_id}",
                           e)
            value_manifests = []  # Continue without value manifests rather than failing completely

        # Create namespace info if available
        namespace_info = None
        if code_list.namespace:
            namespace_info = NamespaceSummary(
                namespace_id=code_list.namespace.namespace_id,
                prefix=code_list.namespace.prefix,
                uri=code_list.namespace.uri
            )

        # Create library info from release
        library_info = LibrarySummary(
            library_id=code_list_manifest.release.library_id,
            name=code_list_manifest.release.library.name
        )

        # Create release info from manifest
        # Since release_id is required and release relationship is loaded, release should always be available
        release_info = ReleaseSummary(
            release_id=code_list_manifest.release_id,
            release_num=code_list_manifest.release.release_num,
            state=code_list_manifest.release.state
        )

        # Create log info from manifest
        log_info = None
        if code_list_manifest.log:
            log_info = LogInfo(
                log_id=code_list_manifest.log.log_id,
                revision_num=code_list_manifest.log.revision_num,
                revision_tracking_num=code_list_manifest.log.revision_tracking_num
            )

        # Create code list values info from value manifests
        code_list_values_info = []
        for value_manifest in value_manifests:
            code_list_values_info.append(CodeListValueDto(
                code_list_value_manifest_id=value_manifest.code_list_value_manifest_id,
                code_list_value_id=value_manifest.code_list_value_id,
                guid=value_manifest.code_list_value.guid,
                value=value_manifest.code_list_value.value,
                meaning=value_manifest.code_list_value.meaning,
                definition=value_manifest.code_list_value.definition,
                is_deprecated=value_manifest.code_list_value.is_deprecated
            ))

        return CodeListDto(
            code_list_manifest_id=code_list_manifest.code_list_manifest_id,
            code_list_id=code_list.code_list_id,
            guid=code_list.guid,
            enum_type_guid=code_list.enum_type_guid,
            name=code_list.name,
            list_id=code_list.list_id,
            version_id=code_list.version_id,
            definition=code_list.definition,
            remark=code_list.remark,
            definition_source=code_list.definition_source,
            namespace=namespace_info,
            library=library_info,
            release=release_info,
            log=log_info,
            extensible_indicator=code_list.extensible_indicator,
            is_deprecated=code_list.is_deprecated,
            state=code_list.state,
            owner=create_user_info(code_list.owner),
            values=code_list_values_info,
            created=WhoAndWhen(
                who=create_user_info(code_list.creator),
                when=code_list.creation_timestamp
            ),
            last_updated=WhoAndWhen(
                who=create_user_info(code_list.last_updater),
                when=code_list.last_update_timestamp
            )
        )
