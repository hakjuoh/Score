"""
Middleware utilities for the Score MCP Server.

This module provides all middleware components including:
- Request/response logging middleware
- Database engine middleware
- App user context middleware
- CORS configuration utilities
"""

import contextvars
import json
import logging
import os
import time
from typing import Optional

from fastmcp.exceptions import ToolError
from fastmcp.server.dependencies import get_access_token, AccessToken
from fastmcp.server.middleware import Middleware as FastMCPMiddleware, MiddlewareContext
from starlette.requests import Request
from starlette.types import ASGIApp

from services.models import AppUser

# Configure logging
logger = logging.getLogger(__name__)

# Create a dedicated logger for request/response logging
request_logger = logging.getLogger("score.mcp.http")

# Create a contextvar to store the database engine
engine_context_var = contextvars.ContextVar("engine_context", default=None)

# Create a contextvar to store the app user
# The default is None, indicating no app user is present
app_user_context_var = contextvars.ContextVar[AppUser | None]("app_user_context", default=None)


class RequestResponseLoggingMiddleware:
    """
    Middleware to log HTTP requests and responses.
    
    Logs request method, path, client IP, response status code, and processing time.
    Optionally logs request/response bodies (limited to avoid logging large payloads).
    """
    
    def __init__(self, app: ASGIApp, max_body_size: int = 1024*1024):
        """
        Initialize the logging middleware.
        
        Args:
            app: The ASGI application to wrap
            max_body_size: Maximum size of request/response body to log (in bytes)
        """
        self.app = app
        self.max_body_size = max_body_size
    
    async def __call__(self, scope, receive, send):
        """ASGI application interface."""
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        
        start_time = time.time()
        
        # Get method and path from scope for logging
        method = scope.get("method", "UNKNOWN")
        path = scope.get("path", "")
        client_ip = scope.get("client", [None])[0] if scope.get("client") else "unknown"
        
        # Capture request body if available and not too large
        request_body = None
        body_size = 0
        body_chunks = []
        
        # Store original receive function before we potentially wrap it
        original_receive = receive
        
        async def receive_with_logging():
            """Wrapper to capture request body while passing through to the app."""
            nonlocal request_body, body_size, body_chunks
            # Use original_receive to avoid recursion
            message = await original_receive()
            if message["type"] == "http.request":
                body = message.get("body", b"")
                more_body = message.get("more_body", False)
                
                if body:
                    body_size += len(body)
                    # Store chunks for later reconstruction
                    body_chunks.append(body)
                    # Only log if total size is within limit
                    if body_size <= self.max_body_size:
                        if request_body is None:
                            request_body = ""
                        try:
                            request_body += body.decode("utf-8", errors="replace")
                        except Exception:
                            pass
                    elif request_body is None:
                        # Mark that we tried to capture but body is too large
                        request_body = ""
                
                # If this is the last chunk and we have no body, mark as empty
                if not more_body and request_body is None and body_size == 0:
                    request_body = ""
                    
            return message
        
        # For POST/PUT/PATCH/DELETE, use the logging wrapper to capture body
        if method in ("POST", "PUT", "PATCH", "DELETE"):
            receive = receive_with_logging
        
        # Create request object for query params and headers
        request = Request(scope, receive)
        
        # Extract session ID from headers for debugging
        session_id = request.headers.get("mcp-session-id", "not provided")
        
        # For methods with body, try to read the first chunk to get params for logging
        # We'll log the request after we have the body or after a short delay
        log_request_immediately = method not in ("POST", "PUT", "PATCH", "DELETE")
        
        if log_request_immediately:
            # Build log message with request parameters
            log_msg = f"Request: {method} {path} from {client_ip}"
            
            # Add query parameters if present
            if request.query_params:
                log_msg += f" query_params={dict(request.query_params)}"
            
            if session_id != "not provided":
                log_msg += f" session_id={session_id}"
            
            request_logger.info(log_msg)
        
        # Track response status and headers
        status_code = None
        response_body = None
        response_session_id = None
        
        async def send_wrapper(message):
            nonlocal status_code, response_body, response_session_id
            if message["type"] == "http.response.start":
                status_code = message["status"]
                # Extract session ID from response headers if present
                headers = message.get("headers", [])
                for header_name, header_value in headers:
                    if header_name.lower() == b"mcp-session-id":
                        response_session_id = header_value.decode("utf-8") if isinstance(header_value, bytes) else header_value
                        break
            elif message["type"] == "http.response.body":
                body = message.get("body", b"")
                if body and len(body) <= self.max_body_size:
                    try:
                        response_body = body.decode("utf-8", errors="replace")
                    except Exception:
                        response_body = f"<binary data: {len(body)} bytes>"
                elif body:
                    response_body = f"<body too large: {len(body)} bytes>"
            await send(message)
        
        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as e:
            status_code = 500
            request_logger.error(
                f"Request failed: {method} {path} - {e}",
                exc_info=True
            )
            raise
        finally:
            # Calculate processing time
            processing_time = time.time() - start_time
            
            # For methods with body, log the complete request with params now that we have the body
            if not log_request_immediately:
                # Build complete log message with request parameters
                log_msg = f"Request: {method} {path} from {client_ip}"
                
                # Add query parameters if present
                if request.query_params:
                    log_msg += f" query_params={dict(request.query_params)}"
                
                if session_id != "not provided":
                    log_msg += f" session_id={session_id}"
                
                # Add request body/parameters if captured
                if request_body is not None:
                    if body_size > self.max_body_size:
                        log_msg += f" params=<body too large: {body_size} bytes>"
                    elif request_body:
                        # Try to parse as JSON for better readability
                        try:
                            parsed_body = json.loads(request_body)
                            log_msg += f" params={parsed_body}"
                        except (json.JSONDecodeError, ValueError):
                            # If not JSON, log as-is (truncate if too long)
                            if len(request_body) > 500:
                                log_msg += f" params=<truncated: {len(request_body)} chars>"
                            else:
                                log_msg += f" params={request_body}"
                
                request_logger.info(log_msg)
            else:
                # For methods without body, log request body separately if it exists (unlikely but possible)
                if request_body is not None and request_body:
                    if body_size > self.max_body_size:
                        request_logger.info(
                            f"Request body too large to log ({body_size} bytes, max: {self.max_body_size})"
                        )
                    else:
                        try:
                            parsed_body = json.loads(request_body)
                            request_logger.info(f"Request params: {parsed_body}")
                        except (json.JSONDecodeError, ValueError):
                            request_logger.info(f"Request params: {request_body}")
            
            # Log response
            log_message = (
                f"Response: {method} {path} "
                f"status={status_code} "
                f"time={processing_time:.3f}s"
            )
            
            if response_session_id:
                log_message += f" session_id={response_session_id}"
            
            # Include response body/object in the log message
            if response_body:
                # Try to parse as JSON for better readability
                try:
                    parsed_response = json.loads(response_body)
                    log_message += f" response={parsed_response}"
                except (json.JSONDecodeError, ValueError):
                    # If not JSON or too large, include a summary
                    if isinstance(response_body, str) and len(response_body) > 500:
                        log_message += f" response=<truncated: {len(response_body)} chars>"
                    else:
                        log_message += f" response={response_body}"
            
            if status_code and status_code >= 500:
                request_logger.error(log_message)
            elif status_code and status_code >= 400:
                request_logger.warning(log_message)
            else:
                request_logger.info(log_message)


class DatabaseEngineMiddleware(FastMCPMiddleware):
    """Middleware to provide database engine access to tools."""

    def __init__(self, engine):
        self.engine = engine

    async def on_request(self, context: MiddlewareContext, call_next):
        """Inject database engine into the context for tools to access."""
        # Store engine in contextvar for tools to access
        engine_context_var.set(self.engine)
        
        try:
            result = await call_next(context)
        finally:
            # Clean up contextvar
            engine_context_var.set(None)

        return result


class AppUserContextMiddleware(FastMCPMiddleware):
    """Middleware to inject app_user info into Context from OAuth2 AccessToken."""

    def __init__(self, engine):
        self.engine = engine
        self.logger = logging.getLogger("score.mcp.auth")

    async def on_request(self, context: MiddlewareContext, call_next):
        """Look up app_user from OAuth2 token's 'sub' claim and inject into context."""
        # Import here to avoid circular import (AppUserService imports transaction which imports middleware)
        from services.app_user import AppUserService
        
        token: Optional[AccessToken] = get_access_token()
        # Log token safely, hiding sensitive values
        if token:
            # Create a safe representation of the token, masking sensitive fields
            token_safe = {}
            # Mask token value if present
            if hasattr(token, 'token') and token.token:
                token_safe['token'] = '**********'
            elif hasattr(token, 'access_token') and token.access_token:
                token_safe['access_token'] = '**********'
            # Include non-sensitive fields
            if hasattr(token, 'claims'):
                token_safe['claims'] = token.claims
            if hasattr(token, 'expires_at'):
                token_safe['expires_at'] = token.expires_at
            if hasattr(token, 'expires_in'):
                token_safe['expires_in'] = token.expires_in
            if hasattr(token, 'scopes'):
                token_safe['scopes'] = token.scopes
            if hasattr(token, 'token_type'):
                token_safe['token_type'] = token.token_type
            self.logger.debug(f"Token: {token_safe}")
        else:
            self.logger.debug("Token: None")
        app_user = None

        # Try OAuth2 token authentication first
        if token is not None and 'sub' in token.claims:
            sub_claim = token.claims['sub']

            # OAuth2 authentication
            try:
                # Look up the user from the OAuth2 sub claim using AppUserService
                app_user_service = AppUserService()
                app_user = app_user_service.get_user_by_oauth2_sub(sub_claim)

                if app_user:
                    self.logger.info(
                        f"Injected app_user from OAuth2 token: {app_user.login_id} (ID: {app_user.app_user_id})")
                else:
                    # Token is present but user is not registered in the database
                    self.logger.warning(f"AppUser not found for OAuth2 sub: {sub_claim}")
                    raise ToolError(
                        "Your account is not yet registered in the system. Please contact your system administrator to set up your account."
                    )
            except ToolError:
                # Re-raise ToolError as-is (already raised above for missing user)
                raise
            except Exception as e:
                # Log the exception with traceback information
                logger.error("Database error during OAuth2 user lookup.", exc_info=True)
                raise ToolError(f"Database error during OAuth2 user lookup: {e}") from e

        # Store app_user in contextvar if found
        if app_user:
            app_user_context_var.set(app_user)

        try:
            result = await call_next(context)
        finally:
            # Clean up contextvar
            app_user_context_var.set(None)

        return result


def parse_cors_config():
    """
    Parse CORS configuration from environment variables.
    
    Environment variables:
    - CORS_ALLOW_ORIGINS: Comma-separated list of allowed origins, or "*" for all (default: "*")
        Example: "https://example.com,https://app.example.com" will be split into a list
    - CORS_ALLOW_METHODS: Comma-separated list of allowed HTTP methods, or "*" for all (default: "*")
        Example: "GET,POST,PUT,DELETE" will be split into a list
    - CORS_ALLOW_HEADERS: Comma-separated list of allowed headers, or "*" for all (default: "*")
        Example: "Content-Type,Authorization" will be split into a list
    - CORS_EXPOSE_HEADERS: Comma-separated list of exposed headers, or "*" for all (default: "*")
        Note: Setting expose_headers=["mcp-session-id"] was attempted to fix session ID issues
        in Docker containers (see https://github.com/modelcontextprotocol/python-sdk/issues/808)
        but did not resolve the "Bad Request: No valid session ID provided" error. Using
        stateless_http=True in FastMCP initialization is the current workaround for Docker deployments.
    - CORS_ALLOW_CREDENTIALS: "true" or "false" (default: false)
    - CORS_MAX_AGE: Maximum age in seconds for preflight requests (default: 600)
    
    Returns:
        dict: Dictionary with CORS configuration parameters
    """
    def parse_list(value: str | None, default: list[str] | str) -> list[str] | str:
        """Parse comma-separated list or return default."""
        if value is None:
            return default
        value = value.strip()
        if value == "*":
            return ["*"]
        # Split by comma and strip whitespace
        return [item.strip() for item in value.split(",") if item.strip()]
    
    def parse_bool(value: str | None, default: bool = False) -> bool:
        """Parse boolean from string."""
        if value is None:
            return default
        return value.lower() in ("true", "1", "yes", "on")
    
    def parse_int(value: str | None, default: int) -> int:
        """Parse integer from string."""
        if value is None:
            return default
        try:
            return int(value)
        except ValueError:
            logger.warning(f"Invalid integer value for CORS_MAX_AGE: {value}, using default: {default}")
            return default
    
    return {
        "allow_origins": parse_list(os.getenv("CORS_ALLOW_ORIGINS"), ["*"]),
        "allow_methods": parse_list(os.getenv("CORS_ALLOW_METHODS"), ["*"]),
        "allow_headers": parse_list(os.getenv("CORS_ALLOW_HEADERS"), ["*"]),
        "expose_headers": parse_list(os.getenv("CORS_EXPOSE_HEADERS"), ["*"]),
        "allow_credentials": parse_bool(os.getenv("CORS_ALLOW_CREDENTIALS"), False),
        "max_age": parse_int(os.getenv("CORS_MAX_AGE"), 600),
    }


# Export contextvar getters for use in other modules
def get_engine():
    """
    Get the database engine from the contextvar.
    
    Returns:
        The database engine if available, None otherwise.
    """
    return engine_context_var.get()


def get_current_user() -> AppUser | None:
    """
    Get the current app_user from the contextvar.
    
    Returns:
        The app_user if available, None otherwise.
    """
    return app_user_context_var.get()
