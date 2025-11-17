"""
Common utility functions for services.
"""
from databases.models import AppUser
from services.models.common import UserSummary, ValueConstraint


def get_user_roles(is_admin: bool, is_developer: bool) -> list[str]:
    """Extract user roles from creator information."""
    roles = []
    if is_admin:
        roles.append('Admin')
    if is_developer:
        roles.append('Developer')
    if not roles:  # Only add End-User if no other roles
        roles.append('End-User')

    return roles


def get_user_roles_by_user(creator: AppUser) -> list[str]:
    """Extract user roles from creator information."""
    if creator is None:
        return []

    return get_user_roles(creator.is_admin, creator.is_developer)


def create_user_info(app_user: AppUser | None) -> UserSummary | None:
    """
    Create UserInfo object from AppUser.
    
    Args:
        app_user: The AppUser instance to convert, or None
        
    Returns:
        UserInfo if app_user is provided, None otherwise
    """
    if app_user is None:
        return None

    roles = get_user_roles_by_user(app_user)
    return UserSummary(
        user_id=app_user.app_user_id,
        login_id=app_user.login_id,
        username=app_user.name or app_user.login_id,
        roles=roles
    )


def validate_and_create_value_constraint(default_value: str | None, fixed_value: str | None) -> ValueConstraint | None:
    """
    Validate and create a ValueConstraint object.

    Validation rules:
    - Can return None if both values are None
    - If not None, exactly one of default_value or fixed_value must be set (not both, not neither)

    Args:
        default_value: Default value for the component
        fixed_value: Fixed value for the component

    Returns:
        ValueConstraint | None: ValueConstraint object if valid, None if both are None

    Raises:
        ValueError: If both values are set or validation fails
    """
    if default_value is None and fixed_value is None:
        return None

    # Validate: exactly one must be set
    has_default = default_value is not None
    has_fixed = fixed_value is not None

    if has_default and has_fixed:
        raise ValueError(
            f"ValueConstraint validation failed: Both default_value and fixed_value cannot be set. "
            f"default_value='{default_value}', fixed_value='{fixed_value}'. "
            f"Exactly one must be set, the other must be None."
        )

    if not has_default and not has_fixed:
        raise ValueError(
            "ValueConstraint validation failed: Either default_value or fixed_value must be set. "
            "Both cannot be None."
        )

    return ValueConstraint(
        default_value=default_value,
        fixed_value=fixed_value
    )
