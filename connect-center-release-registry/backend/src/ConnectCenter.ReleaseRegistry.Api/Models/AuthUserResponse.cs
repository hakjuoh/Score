namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record AuthUserResponse(
    long AppUserId,
    string Username,
    string DisplayName,
    bool IsAdmin,
    bool IsDeveloper);
