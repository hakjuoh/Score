import logging
import os
from urllib.parse import urlparse

# SQLModel's create_engine returns sqlalchemy.engine.Engine - import for type hints
import sqlalchemy
from sqlalchemy.engine import Engine

# Configure logging
logger = logging.getLogger("score.database.engine")


def create_engine() -> tuple[Engine, str]:
    # Create database engine for MariaDB
    # Database configuration from environment variables
    # Support both DATABASE_URL (direct) or individual components
    DATABASE_URL = os.getenv("DATABASE_URL")
    
    # Check if DATABASE_URL contains variable substitution syntax (not supported by python-dotenv)
    if DATABASE_URL and "${" in DATABASE_URL:
        logger.warning(
            "DATABASE_URL contains variable substitution syntax (${VAR}), which is not supported by python-dotenv. "
            "The ${VAR} syntax will be treated as a literal string, resulting in an invalid database URL. "
            "Please either:\n"
            "  1. Set DATABASE_URL with actual values (not ${VAR} syntax), or\n"
            "  2. Remove DATABASE_URL and set individual DATABASE_* variables (DATABASE_CONNECTOR, DATABASE_USERNAME, etc.) "
            "     and let the application construct DATABASE_URL automatically.\n"
            f"Current DATABASE_URL value: {DATABASE_URL}"
        )
        # Treat as if DATABASE_URL is not set, so we construct it from individual components
        DATABASE_URL = None
    
    if not DATABASE_URL:
        # Construct DATABASE_URL from individual components if not provided
        connector = os.getenv("DATABASE_CONNECTOR", "mariadb+mariadbconnector")
        username = os.getenv("DATABASE_USERNAME", "root")
        password = os.getenv("DATABASE_PASSWORD", "")
        hostname = os.getenv("DATABASE_HOSTNAME", "127.0.0.1")
        port = os.getenv("DATABASE_PORT", "3306")
        database = os.getenv("DATABASE_NAME", "")

        DATABASE_URL = f"{connector}://{username}:{password}@{hostname}:{port}/{database}"

    # Validate that DATABASE_URL is set
    if not DATABASE_URL:
        raise ValueError(
            "DATABASE_URL is not configured. Please set either:\n"
            "  - DATABASE_URL environment variable, or\n"
            "  - Individual DATABASE_* environment variables (DATABASE_HOSTNAME, DATABASE_USERNAME, etc.)"
        )

    # Validate that DATABASE_URL is a valid URL format
    try:
        parsed = urlparse(DATABASE_URL)
        if not parsed.scheme:
            raise ValueError("DATABASE_URL must include a scheme (e.g., 'mariadb+mariadbconnector://')")
        if not parsed.hostname:
            raise ValueError("DATABASE_URL must include a hostname")
    except Exception as e:
        if isinstance(e, ValueError):
            raise
        raise ValueError(
            f"DATABASE_URL is not a valid URL format: {DATABASE_URL}\n"
            f"Error: {str(e)}\n"
            f"Expected format: scheme://username:password@hostname:port/database"
        ) from e

    # Configure SQLAlchemy logging based on environment variable
    sqlalchemy_log = os.getenv("SQLALCHEMY_LOG", "").lower() in ("true", "1", "yes", "on")
    enable_sqlalchemy_logging = sqlalchemy_log
    
    if enable_sqlalchemy_logging:
        # Get the log level for SQLAlchemy (defaults to LOG_LEVEL if not specified)
        # First, get LOG_LEVEL to use as default
        log_level_str = os.getenv("LOG_LEVEL", "INFO").upper()
        log_level_map = {
            "DEBUG": logging.DEBUG,
            "INFO": logging.INFO,
            "WARNING": logging.WARNING,
            "ERROR": logging.ERROR,
            "CRITICAL": logging.CRITICAL,
        }
        default_log_level = log_level_map.get(log_level_str, logging.INFO)
        
        # Get SQLALCHEMY_LOG_LEVEL, defaulting to LOG_LEVEL if not provided
        sqlalchemy_log_level_str = os.getenv("SQLALCHEMY_LOG_LEVEL", log_level_str).upper()
        sqlalchemy_log_level_map = {
            "DEBUG": logging.DEBUG,
            "INFO": logging.INFO,
            "WARNING": logging.WARNING,
            "ERROR": logging.ERROR,
            "CRITICAL": logging.CRITICAL,
        }
        sqlalchemy_log_level = sqlalchemy_log_level_map.get(sqlalchemy_log_level_str, default_log_level)
        
        # Configure SQLAlchemy loggers
        # sqlalchemy.engine logs SQL statements and connection events
        logging.getLogger("sqlalchemy.engine").setLevel(sqlalchemy_log_level)
        logging.getLogger("sqlalchemy.engine").propagate = True
        
        # sqlalchemy.pool logs connection pool events
        logging.getLogger("sqlalchemy.pool").setLevel(sqlalchemy_log_level)
        logging.getLogger("sqlalchemy.pool").propagate = True
        
        # sqlalchemy.dialects logs dialect-specific events
        logging.getLogger("sqlalchemy.dialects").setLevel(sqlalchemy_log_level)
        logging.getLogger("sqlalchemy.dialects").propagate = True
        
        logger.info(f"SQLAlchemy logging enabled at level: {sqlalchemy_log_level_str} ({sqlalchemy_log_level})")
    else:
        # Disable SQLAlchemy logging by setting to a high level
        logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)
        logging.getLogger("sqlalchemy.pool").setLevel(logging.WARNING)
        logging.getLogger("sqlalchemy.dialects").setLevel(logging.WARNING)
        logger.debug("SQLAlchemy logging disabled (set SQLALCHEMY_LOG=true in .env to enable)")

    return sqlalchemy.create_engine(DATABASE_URL, echo=enable_sqlalchemy_logging), DATABASE_URL

