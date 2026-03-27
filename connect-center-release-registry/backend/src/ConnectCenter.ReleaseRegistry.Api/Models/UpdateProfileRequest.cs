namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record UpdateProfileRequest(
    string DisplayName,
    string CurrentPassword,
    string? NewPassword);
