using ConnectCenter.ReleaseRegistry.Api.Models;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public interface IAuthService
{
    Task<AuthUserResponse?> AuthenticateAsync(string username, string password, CancellationToken cancellationToken);

    Task<AuthUserResponse?> GetUserAsync(long appUserId, CancellationToken cancellationToken);

    Task<UpdateProfileResult> UpdateProfileAsync(
        long appUserId,
        string displayName,
        string currentPassword,
        string? newPassword,
        CancellationToken cancellationToken);
}
