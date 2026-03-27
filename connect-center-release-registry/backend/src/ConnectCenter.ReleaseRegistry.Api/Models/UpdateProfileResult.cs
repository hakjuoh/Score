namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record UpdateProfileResult(
    bool Success,
    string? Error,
    AuthUserResponse? User);
