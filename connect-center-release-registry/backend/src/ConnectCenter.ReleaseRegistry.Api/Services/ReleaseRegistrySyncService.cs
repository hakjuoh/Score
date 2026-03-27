using ConnectCenter.ReleaseRegistry.Api.Options;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;
using MySqlConnector;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public sealed class ReleaseRegistrySyncService(
    SqliteRegistryStore sqliteStore,
    IOptions<MariaDbSourceOptions> mariaDbOptions,
    ILogger<ReleaseRegistrySyncService> logger)
{
    public async Task SyncAsync(CancellationToken cancellationToken)
    {
        var databasePath = sqliteStore.DatabasePath;
        var tempPath = $"{databasePath}.tmp";
        var directory = Path.GetDirectoryName(databasePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        if (File.Exists(tempPath))
        {
            File.Delete(tempPath);
        }
        DeleteSidecarFiles(tempPath);

        try
        {
            var existingExportDataLinks = await LoadExistingExportDataLinksAsync(databasePath, cancellationToken);
            var existingUsers = await LoadExistingUsersAsync(databasePath, cancellationToken);

            await using var source = new MySqlConnection(mariaDbOptions.Value.ToConnectionString());
            await source.OpenAsync(cancellationToken);

            await using var destination = new SqliteConnection($"Data Source={tempPath};Mode=ReadWriteCreate;Cache=Shared");
            await destination.OpenAsync(cancellationToken);
            await SqliteRegistrySchema.CreateAsync(destination, cancellationToken);

            await using var transaction = (SqliteTransaction) await destination.BeginTransactionAsync(cancellationToken);

            await CopyUsersAsync(source, destination, transaction, existingUsers, cancellationToken);
            await CopyLibrariesAsync(source, destination, transaction, cancellationToken);
            await CopyNamespacesAsync(source, destination, transaction, cancellationToken);
            await CopyReleasesAsync(source, destination, transaction, existingExportDataLinks, cancellationToken);
            await CopyReleaseDependenciesAsync(source, destination, transaction, cancellationToken);
            await SqliteAuthService.EnsureDefaultAdminUserAsync(
                destination,
                transaction,
                cancellationToken,
                existingUsers.TryGetValue(SqliteAuthService.DefaultAdminUserId, out var existingAdmin)
                    ? existingAdmin
                    : null);
            await UpsertSyncInfoAsync(destination, transaction, cancellationToken);

            await transaction.CommitAsync(cancellationToken);
            await destination.CloseAsync();

            if (File.Exists(databasePath))
            {
                File.Delete(databasePath);
            }
            DeleteSidecarFiles(databasePath);
            File.Move(tempPath, databasePath, overwrite: true);
            logger.LogInformation("SQLite release registry refreshed at {DatabasePath}.", databasePath);
        }
        catch (Exception ex) when (File.Exists(databasePath))
        {
            logger.LogWarning(ex, "Failed to refresh SQLite snapshot. Continuing with the existing database at {DatabasePath}.", databasePath);
            if (File.Exists(tempPath))
            {
                File.Delete(tempPath);
            }
        }
    }

    private static async Task CopyUsersAsync(
        MySqlConnection source,
        SqliteConnection destination,
        SqliteTransaction transaction,
        IReadOnlyDictionary<long, ExistingAppUserSnapshot> existingUsers,
        CancellationToken cancellationToken)
    {
        const string selectSql =
            """
            SELECT
                app_user_id,
                login_id,
                password,
                name,
                organization,
                email,
                COALESCE(is_developer, 0),
                COALESCE(is_admin, 0),
                COALESCE(is_enabled, 1)
            FROM app_user
            ORDER BY app_user_id
            """;

        await using var select = new MySqlCommand(selectSql, source);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            var appUserId = checked((long) reader.GetUInt64(0));
            existingUsers.TryGetValue(appUserId, out var existingUser);

            await using var insert = destination.CreateCommand();
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
            insert.Parameters.AddWithValue("$app_user_id", appUserId);
            insert.Parameters.AddWithValue("$login_id", existingUser?.LoginId ?? reader.GetString(1));
            insert.Parameters.AddWithValue("$password", (object?) existingUser?.Password ?? DbValue(reader, 2));
            insert.Parameters.AddWithValue("$name", (object?) existingUser?.Name ?? DbValue(reader, 3));
            insert.Parameters.AddWithValue("$organization", (object?) existingUser?.Organization ?? DbValue(reader, 4));
            insert.Parameters.AddWithValue("$email", (object?) existingUser?.Email ?? DbValue(reader, 5));
            insert.Parameters.AddWithValue("$is_developer", existingUser?.IsDeveloper == true || reader.GetBoolean(6) ? 1 : 0);
            insert.Parameters.AddWithValue("$is_admin", existingUser?.IsAdmin == true || reader.GetBoolean(7) ? 1 : 0);
            insert.Parameters.AddWithValue("$is_enabled", existingUser?.IsEnabled == false ? 0 : (reader.GetBoolean(8) ? 1 : 0));
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task CopyLibrariesAsync(
        MySqlConnection source,
        SqliteConnection destination,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string selectSql =
            """
            SELECT
                library_id,
                COALESCE(name, ''),
                COALESCE(type, ''),
                COALESCE(organization, ''),
                COALESCE(description, ''),
                COALESCE(link, ''),
                COALESCE(domain, ''),
                COALESCE(state, ''),
                COALESCE(is_read_only, 0),
                COALESCE(is_default, 0),
                created_by,
                last_updated_by,
                creation_timestamp,
                last_update_timestamp
            FROM library
            ORDER BY library_id
            """;

        await using var select = new MySqlCommand(selectSql, source);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            await using var insert = destination.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText =
                """
                INSERT INTO library (
                    library_id, name, type, organization, description, link, domain, state,
                    is_read_only, is_default, created_by, last_updated_by,
                    creation_timestamp, last_update_timestamp
                ) VALUES (
                    $library_id, $name, $type, $organization, $description, $link, $domain, $state,
                    $is_read_only, $is_default, $created_by, $last_updated_by,
                    $creation_timestamp, $last_update_timestamp
                )
                """;
            insert.Parameters.AddWithValue("$library_id", reader.GetUInt64(0));
            insert.Parameters.AddWithValue("$name", reader.GetString(1));
            insert.Parameters.AddWithValue("$type", reader.GetString(2));
            insert.Parameters.AddWithValue("$organization", reader.GetString(3));
            insert.Parameters.AddWithValue("$description", reader.GetString(4));
            insert.Parameters.AddWithValue("$link", reader.GetString(5));
            insert.Parameters.AddWithValue("$domain", reader.GetString(6));
            insert.Parameters.AddWithValue("$state", reader.GetString(7));
            insert.Parameters.AddWithValue("$is_read_only", reader.GetBoolean(8) ? 1 : 0);
            insert.Parameters.AddWithValue("$is_default", reader.GetBoolean(9) ? 1 : 0);
            insert.Parameters.AddWithValue("$created_by", reader.GetUInt64(10));
            insert.Parameters.AddWithValue("$last_updated_by", reader.GetUInt64(11));
            insert.Parameters.AddWithValue("$creation_timestamp", ToIsoString(reader.GetDateTime(12)));
            insert.Parameters.AddWithValue("$last_update_timestamp", ToIsoString(reader.GetDateTime(13)));
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task CopyNamespacesAsync(
        MySqlConnection source,
        SqliteConnection destination,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string selectSql =
            """
            SELECT
                namespace_id,
                library_id,
                uri,
                prefix,
                description
            FROM namespace
            ORDER BY namespace_id
            """;

        await using var select = new MySqlCommand(selectSql, source);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            await using var insert = destination.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText =
                """
                INSERT INTO namespace (
                    namespace_id, library_id, uri, prefix, description
                ) VALUES (
                    $namespace_id, $library_id, $uri, $prefix, $description
                )
                """;
            insert.Parameters.AddWithValue("$namespace_id", reader.GetUInt64(0));
            insert.Parameters.AddWithValue("$library_id", reader.GetUInt64(1));
            insert.Parameters.AddWithValue("$uri", reader.GetString(2));
            insert.Parameters.AddWithValue("$prefix", DbValue(reader, 3));
            insert.Parameters.AddWithValue("$description", DbValue(reader, 4));
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task CopyReleasesAsync(
        MySqlConnection source,
        SqliteConnection destination,
        SqliteTransaction transaction,
        IReadOnlyDictionary<long, string> existingExportDataLinks,
        CancellationToken cancellationToken)
    {
        const string selectSql =
            """
            SELECT
                release_id,
                library_id,
                guid,
                COALESCE(release_num, ''),
                release_note,
                release_license,
                namespace_id,
                created_by,
                last_updated_by,
                creation_timestamp,
                last_update_timestamp,
                COALESCE(state, ''),
                prev_release_id,
                next_release_id
            FROM `release`
            WHERE COALESCE(release_num, '') <> 'Working'
            ORDER BY release_id
            """;

        await using var select = new MySqlCommand(selectSql, source);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            await using var insert = destination.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText =
                """
                INSERT INTO "release" (
                    release_id, library_id, guid, release_num, release_note, release_license,
                    namespace_id, created_by, last_updated_by, creation_timestamp,
                    last_update_timestamp, state, prev_release_id, next_release_id, export_data_link
                ) VALUES (
                    $release_id, $library_id, $guid, $release_num, $release_note, $release_license,
                    $namespace_id, $created_by, $last_updated_by, $creation_timestamp,
                    $last_update_timestamp, $state, $prev_release_id, $next_release_id, $export_data_link
                )
                """;
            var releaseId = checked((long) reader.GetUInt64(0));

            insert.Parameters.AddWithValue("$release_id", releaseId);
            insert.Parameters.AddWithValue("$library_id", reader.GetUInt64(1));
            insert.Parameters.AddWithValue("$guid", reader.GetString(2));
            insert.Parameters.AddWithValue("$release_num", reader.GetString(3));
            insert.Parameters.AddWithValue("$release_note", DbValue(reader, 4));
            insert.Parameters.AddWithValue("$release_license", DbValue(reader, 5));
            insert.Parameters.AddWithValue("$namespace_id", reader.IsDBNull(6) ? DBNull.Value : reader.GetUInt64(6));
            insert.Parameters.AddWithValue("$created_by", reader.GetUInt64(7));
            insert.Parameters.AddWithValue("$last_updated_by", reader.GetUInt64(8));
            insert.Parameters.AddWithValue("$creation_timestamp", ToIsoString(reader.GetDateTime(9)));
            insert.Parameters.AddWithValue("$last_update_timestamp", ToIsoString(reader.GetDateTime(10)));
            insert.Parameters.AddWithValue("$state", reader.GetString(11));
            insert.Parameters.AddWithValue("$prev_release_id", reader.IsDBNull(12) ? DBNull.Value : reader.GetUInt64(12));
            insert.Parameters.AddWithValue("$next_release_id", reader.IsDBNull(13) ? DBNull.Value : reader.GetUInt64(13));
            insert.Parameters.AddWithValue(
                "$export_data_link",
                existingExportDataLinks.TryGetValue(releaseId, out var exportDataLink) && !string.IsNullOrWhiteSpace(exportDataLink)
                    ? exportDataLink
                    : CreateDefaultExportDataLink(releaseId));
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task CopyReleaseDependenciesAsync(
        MySqlConnection source,
        SqliteConnection destination,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        const string selectSql =
            """
            SELECT
                rd.release_dep_id,
                rd.release_id,
                rd.depend_on_release_id
            FROM release_dep rd
            JOIN `release` r ON r.release_id = rd.release_id
            JOIN `release` dr ON dr.release_id = rd.depend_on_release_id
            WHERE COALESCE(r.release_num, '') <> 'Working'
              AND COALESCE(dr.release_num, '') <> 'Working'
            ORDER BY rd.release_dep_id
            """;

        await using var select = new MySqlCommand(selectSql, source);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            await using var insert = destination.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText =
                """
                INSERT INTO release_dep (
                    release_dep_id, release_id, depend_on_release_id
                ) VALUES (
                    $release_dep_id, $release_id, $depend_on_release_id
                )
                """;
            insert.Parameters.AddWithValue("$release_dep_id", reader.GetUInt64(0));
            insert.Parameters.AddWithValue("$release_id", reader.GetUInt64(1));
            insert.Parameters.AddWithValue("$depend_on_release_id", reader.GetUInt64(2));
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }
    }

    private static async Task UpsertSyncInfoAsync(
        SqliteConnection connection,
        SqliteTransaction transaction,
        CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText =
            """
            INSERT INTO sync_info (key, value) VALUES ('synced_at_utc', $value)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        command.Parameters.AddWithValue("$value", DateTimeOffset.UtcNow.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    private static async Task<Dictionary<long, string>> LoadExistingExportDataLinksAsync(
        string databasePath,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(databasePath))
        {
            return [];
        }

        await using var connection = new SqliteConnection($"Data Source={databasePath};Mode=ReadOnly;Cache=Shared");
        await connection.OpenAsync(cancellationToken);

        await using (var pragma = connection.CreateCommand())
        {
            pragma.CommandText = "PRAGMA table_info(\"release\")";
            await using var reader = await pragma.ExecuteReaderAsync(cancellationToken);
            var hasExportDataLink = false;
            while (await reader.ReadAsync(cancellationToken))
            {
                if (string.Equals(reader.GetString(1), "export_data_link", StringComparison.OrdinalIgnoreCase))
                {
                    hasExportDataLink = true;
                    break;
                }
            }

            if (!hasExportDataLink)
            {
                return [];
            }
        }

        var result = new Dictionary<long, string>();
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT release_id, export_data_link
            FROM "release"
            WHERE export_data_link IS NOT NULL
              AND trim(export_data_link) <> ''
            """;

        await using var linksReader = await command.ExecuteReaderAsync(cancellationToken);
        while (await linksReader.ReadAsync(cancellationToken))
        {
            result[linksReader.GetInt64(0)] = linksReader.GetString(1);
        }

        return result;
    }

    private static async Task<Dictionary<long, ExistingAppUserSnapshot>> LoadExistingUsersAsync(
        string databasePath,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(databasePath))
        {
            return [];
        }

        await using var connection = new SqliteConnection($"Data Source={databasePath};Mode=ReadOnly;Cache=Shared");
        await connection.OpenAsync(cancellationToken);

        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                app_user_id,
                login_id,
                password,
                name,
                organization,
                email,
                is_developer,
                is_admin,
                is_enabled
            FROM app_user
            """;

        var result = new Dictionary<long, ExistingAppUserSnapshot>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            var snapshot = new ExistingAppUserSnapshot(
                AppUserId: reader.GetInt64(0),
                LoginId: reader.GetString(1),
                Password: reader.IsDBNull(2) ? null : reader.GetString(2),
                Name: reader.IsDBNull(3) ? null : reader.GetString(3),
                Organization: reader.IsDBNull(4) ? null : reader.GetString(4),
                Email: reader.IsDBNull(5) ? null : reader.GetString(5),
                IsDeveloper: reader.GetInt64(6) == 1,
                IsAdmin: reader.GetInt64(7) == 1,
                IsEnabled: reader.GetInt64(8) == 1);
            result[snapshot.AppUserId] = snapshot;
        }

        return result;
    }

    private static string ToIsoString(DateTime value)
    {
        return new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)).ToString("O");
    }

    private static string CreateDefaultExportDataLink(long releaseId)
    {
        return $"/api/releases/{releaseId}/export";
    }

    private static object DbValue(MySqlDataReader reader, int ordinal)
    {
        return reader.IsDBNull(ordinal) ? DBNull.Value : reader.GetValue(ordinal);
    }

    private static void DeleteSidecarFiles(string databasePath)
    {
        foreach (var suffix in new[] { "-shm", "-wal" })
        {
            var sidecarPath = databasePath + suffix;
            if (File.Exists(sidecarPath))
            {
                File.Delete(sidecarPath);
            }
        }
    }
}
