using ConnectCenter.ReleaseRegistry.Api.Models;
using Microsoft.Data.Sqlite;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public sealed class SqliteAuthService(
    SqliteRegistryStore sqliteStore,
    ILogger<SqliteAuthService> logger) : IAuthService
{
    public const long DefaultAdminUserId = -1;
    private const string DefaultAdminUsername = "admin";
    private const string DefaultAdminDisplayName = "Administrator";
    private const string DefaultAdminPassword = "admin";

    public async Task<AuthUserResponse?> AuthenticateAsync(string username, string password, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(password))
        {
            logger.LogWarning("Authentication rejected because username or password was blank.");
            return null;
        }

        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);

        var record = await FindByUsernameAsync(connection, null, username, cancellationToken);
        if (record is null)
        {
            logger.LogWarning("Authentication failed for '{Username}': user was not found.", username.Trim());
            return null;
        }

        if (!record.IsEnabled)
        {
            logger.LogWarning("Authentication failed for '{Username}': user is disabled.", username.Trim());
            return null;
        }

        if (!PasswordHasher.Verify(record.Password, password))
        {
            logger.LogWarning(
                "Authentication failed for '{Username}': password verification failed. StoredPasswordFormat={PasswordFormat}",
                username.Trim(),
                PasswordHasher.DescribeFormat(record.Password));
            return null;
        }

        if (PasswordHasher.ShouldUpgradeHash(record.Password))
        {
            await UpdatePasswordHashAsync(connection, null, record.AppUserId, PasswordHasher.Hash(password), cancellationToken);
            record = record with { Password = PasswordHasher.Hash(password) };
            logger.LogInformation(
                "Authentication succeeded for '{Username}' and the password was upgraded to the local PBKDF2 format.",
                username.Trim());
        }
        else
        {
            logger.LogInformation(
                "Authentication succeeded for '{Username}' (AppUserId={AppUserId}).",
                username.Trim(),
                record.AppUserId);
        }

        return ToResponse(record);
    }

    public async Task<AuthUserResponse?> GetUserAsync(long appUserId, CancellationToken cancellationToken)
    {
        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);

        var record = await FindByIdAsync(connection, null, appUserId, cancellationToken);
        if (record is null)
        {
            logger.LogWarning("Profile lookup failed for AppUserId={AppUserId}: user not found.", appUserId);
        }
        return record is null || !record.IsEnabled ? null : ToResponse(record);
    }

    public async Task<UpdateProfileResult> UpdateProfileAsync(
        long appUserId,
        string displayName,
        string currentPassword,
        string? newPassword,
        CancellationToken cancellationToken)
    {
        var normalizedDisplayName = displayName.Trim();
        var normalizedPassword = newPassword?.Trim();
        if (string.IsNullOrWhiteSpace(normalizedDisplayName))
        {
            logger.LogWarning("Profile update rejected for AppUserId={AppUserId}: display name was blank.", appUserId);
            return new UpdateProfileResult(false, "Display name is required.", null);
        }

        if (string.IsNullOrWhiteSpace(currentPassword))
        {
            logger.LogWarning("Profile update rejected for AppUserId={AppUserId}: current password was blank.", appUserId);
            return new UpdateProfileResult(false, "Current password is required.", null);
        }

        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);
        await using var transaction = (SqliteTransaction) await connection.BeginTransactionAsync(cancellationToken);

        var record = await FindByIdAsync(connection, transaction, appUserId, cancellationToken);
        if (record is null || !record.IsEnabled)
        {
            logger.LogWarning("Profile update rejected for AppUserId={AppUserId}: user not found or disabled.", appUserId);
            return new UpdateProfileResult(false, "User profile was not found.", null);
        }

        if (!PasswordHasher.Verify(record.Password, currentPassword))
        {
            logger.LogWarning(
                "Profile update rejected for AppUserId={AppUserId}: current password verification failed. StoredPasswordFormat={PasswordFormat}",
                appUserId,
                PasswordHasher.DescribeFormat(record.Password));
            return new UpdateProfileResult(false, "Current password is incorrect.", null);
        }

        var passwordToStore = string.IsNullOrWhiteSpace(normalizedPassword)
            ? (PasswordHasher.IsManagedHash(record.Password) ? record.Password! : PasswordHasher.Hash(currentPassword))
            : PasswordHasher.Hash(normalizedPassword);

        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText =
            """
            UPDATE app_user
            SET name = $name,
                password = $password
            WHERE app_user_id = $app_user_id
            """;
        command.Parameters.AddWithValue("$name", normalizedDisplayName);
        command.Parameters.AddWithValue("$password", passwordToStore);
        command.Parameters.AddWithValue("$app_user_id", appUserId);
        await command.ExecuteNonQueryAsync(cancellationToken);

        await transaction.CommitAsync(cancellationToken);
        logger.LogInformation(
            "Profile updated for AppUserId={AppUserId}. DisplayNameChanged={DisplayNameChanged} PasswordChanged={PasswordChanged}",
            appUserId,
            !string.Equals(record.Name ?? record.LoginId, normalizedDisplayName, StringComparison.Ordinal),
            !string.IsNullOrWhiteSpace(normalizedPassword));

        var updated = await GetUserAsync(appUserId, cancellationToken);
        return new UpdateProfileResult(true, null, updated);
    }

    public static async Task EnsureDefaultAdminUserAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        CancellationToken cancellationToken,
        ExistingAppUserSnapshot? existingAdmin = null)
    {
        await using var countCommand = connection.CreateCommand();
        countCommand.Transaction = transaction;
        countCommand.CommandText = "SELECT COUNT(*) FROM app_user";
        var appUserCount = Convert.ToInt32(await countCommand.ExecuteScalarAsync(cancellationToken));
        if (appUserCount > 0)
        {
            return;
        }

        await using var insert = connection.CreateCommand();
        insert.Transaction = transaction;
        insert.CommandText =
            """
            INSERT INTO app_user (
                app_user_id, login_id, password, name, organization, email,
                is_developer, is_admin, is_enabled
            ) VALUES (
                $app_user_id, $login_id, $password, $name, $organization, $email,
                $is_developer, $is_admin, $is_enabled
            )
            """;
        insert.Parameters.AddWithValue("$app_user_id", DefaultAdminUserId);
        insert.Parameters.AddWithValue("$login_id", existingAdmin?.LoginId ?? DefaultAdminUsername);
        insert.Parameters.AddWithValue(
            "$password",
            string.IsNullOrWhiteSpace(existingAdmin?.Password)
                ? PasswordHasher.Hash(DefaultAdminPassword)
                : existingAdmin!.Password);
        insert.Parameters.AddWithValue("$name", existingAdmin?.Name ?? DefaultAdminDisplayName);
        insert.Parameters.AddWithValue("$organization", existingAdmin?.Organization ?? "connectCenter");
        insert.Parameters.AddWithValue("$email", (object?) existingAdmin?.Email ?? DBNull.Value);
        insert.Parameters.AddWithValue("$is_developer", existingAdmin?.IsDeveloper == true ? 1 : 0);
        insert.Parameters.AddWithValue("$is_admin", existingAdmin?.IsAdmin == false ? 0 : 1);
        insert.Parameters.AddWithValue("$is_enabled", existingAdmin?.IsEnabled == false ? 0 : 1);
        await insert.ExecuteNonQueryAsync(cancellationToken);
    }

    private static async Task UpdatePasswordHashAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        long appUserId,
        string passwordHash,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText =
            """
            UPDATE app_user
            SET password = $password
            WHERE app_user_id = $app_user_id
            """;
        command.Parameters.AddWithValue("$password", passwordHash);
        command.Parameters.AddWithValue("$app_user_id", appUserId);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    private static async Task<AppUserRecord?> FindByIdAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        long appUserId,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText =
            """
            SELECT app_user_id, login_id, password, name, is_admin, is_developer, is_enabled
            FROM app_user
            WHERE app_user_id = $app_user_id
            LIMIT 1
            """;
        command.Parameters.AddWithValue("$app_user_id", appUserId);
        return await ReadSingleAsync(command, cancellationToken);
    }

    private static async Task<AppUserRecord?> FindByUsernameAsync(
        SqliteConnection connection,
        SqliteTransaction? transaction,
        string username,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText =
            """
            SELECT app_user_id, login_id, password, name, is_admin, is_developer, is_enabled
            FROM app_user
            WHERE lower(login_id) = lower($login_id)
            LIMIT 1
            """;
        command.Parameters.AddWithValue("$login_id", username.Trim());
        return await ReadSingleAsync(command, cancellationToken);
    }

    private static async Task<AppUserRecord?> ReadSingleAsync(SqliteCommand command, CancellationToken cancellationToken)
    {
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new AppUserRecord(
            AppUserId: reader.GetInt64(0),
            LoginId: reader.GetString(1),
            Password: reader.IsDBNull(2) ? null : reader.GetString(2),
            Name: reader.IsDBNull(3) ? null : reader.GetString(3),
            IsAdmin: reader.GetInt64(4) == 1,
            IsDeveloper: reader.GetInt64(5) == 1,
            IsEnabled: reader.GetInt64(6) == 1);
    }

    private static AuthUserResponse ToResponse(AppUserRecord record)
    {
        return new AuthUserResponse(
            AppUserId: record.AppUserId,
            Username: record.LoginId,
            DisplayName: string.IsNullOrWhiteSpace(record.Name) ? record.LoginId : record.Name,
            IsAdmin: record.IsAdmin,
            IsDeveloper: record.IsDeveloper);
    }

    private sealed record AppUserRecord(
        long AppUserId,
        string LoginId,
        string? Password,
        string? Name,
        bool IsAdmin,
        bool IsDeveloper,
        bool IsEnabled);
}

public sealed record ExistingAppUserSnapshot(
    long AppUserId,
    string LoginId,
    string? Password,
    string? Name,
    string? Organization,
    string? Email,
    bool IsDeveloper,
    bool IsAdmin,
    bool IsEnabled);
