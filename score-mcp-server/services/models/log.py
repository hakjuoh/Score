"""Generic models for Log domain."""
from pydantic import BaseModel


class LogInfo(BaseModel):
    """Log information object."""
    log_id: int  # Unique identifier for the log entry
    revision_num: int  # Revision number indicating the version of the logged item
    revision_tracking_num: int  # Revision tracking number used for tracking changes across revisions

