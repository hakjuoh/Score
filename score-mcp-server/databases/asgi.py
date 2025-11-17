import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator, Any

from fastmcp import FastMCP
from mcp.server.lowlevel.server import LifespanResultT
from sqlalchemy.engine import Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import text

# Configure logging
logger = logging.getLogger("score.database.asgi")


def create_database_lifespan(engine: Engine, database_url: str):
    """
    Create a lifespan context manager for FastMCP application that performs
    database connectivity check during startup.
    
    Args:
        engine: The SQLAlchemy database engine
        database_url: The database URL string (for error messages)
    
    Returns:
        An async context manager function that can be used as FastMCP lifespan
    """
    @asynccontextmanager
    async def lifespan(server: FastMCP[LifespanResultT]) -> AsyncIterator[Any]:
        """
        Lifespan context manager for FastMCP application.
        
        Performs database connectivity check during startup to ensure the database
        is accessible before the application starts accepting requests. This provides
        fail-fast behavior if the database connection cannot be established.
        
        Args:
            mcp: The FastMCP instance (provided by FastMCP framework)
        
        Raises:
            RuntimeError: If database connection test fails during startup
        """
        # Startup: Test database connectivity
        logger.info("Starting database connectivity check...")
        try:
            # Test database connection by executing a simple query
            with engine.connect() as conn:
                result = conn.execute(text("SELECT 1"))
                result.fetchone()
            logger.info("✓ Database connectivity check passed successfully")
        except SQLAlchemyError as e:
            error_msg = (
                f"❌ Database connectivity check failed during startup. "
                f"Cannot connect to database at {database_url.split('@')[-1] if '@' in database_url else 'configured location'}. "
                f"Error: {str(e)}. "
                f"Please verify your DATABASE_URL configuration and ensure the database server is running and accessible."
            )
            logger.error(error_msg)
            raise RuntimeError(error_msg) from e
        except Exception as e:
            error_msg = (
                f"❌ Unexpected error during database connectivity check: {str(e)}. "
                f"Please check your database configuration and server status."
            )
            logger.error(error_msg)
            raise RuntimeError(error_msg) from e
        
        yield
        
        # Shutdown: Clean up database connections
        logger.info("Shutting down database connections...")
        engine.dispose()
        logger.info("✓ Database connections closed")
    
    return lifespan


def create_http_app_lifespan_wrapper(mcp_instance: FastMCP, mcp_lifespan):
    """
    Create a wrapper lifespan function for Starlette http_app that adapts
    the FastMCP lifespan function.
    
    The original lifespan function expects a FastMCP instance, but the
    Starlette app's lifespan_context receives the app instance. This wrapper
    adapts the call to pass the mcp_instance to the original lifespan function.
    
    Args:
        mcp_instance: The FastMCP instance to pass to the original lifespan
        mcp_lifespan: The original lifespan function that accepts FastMCP
    
    Returns:
        A lifespan function compatible with Starlette's lifespan_context
    """
    @asynccontextmanager
    async def http_app_lifespan(app) -> AsyncIterator[Any]:
        """
        Wrapper lifespan for Starlette http_app.
        
        This adapts the FastMCP lifespan to work with Starlette's lifespan_context
        by passing the mcp_instance to the original lifespan function.
        
        Args:
            app: The Starlette application instance (provided by the framework)
        """
        async with mcp_lifespan(mcp_instance):
            yield
    
    return http_app_lifespan

