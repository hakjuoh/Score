"""Models for Agency ID List tools."""
from __future__ import annotations

from services.models.common import PaginationResponse
from services.models.agency_id_list import AgencyIdListDto


class GetAgencyIdListResponse(AgencyIdListDto):
    """Response for get_agency_id_list tool."""
    pass


class GetAgencyIdListPaginationResponse(PaginationResponse[GetAgencyIdListResponse]):
    """Response for get_agency_id_lists tool."""
    pass

