using System.Globalization;
using ConnectCenter.ReleaseRegistry.Api.Models;
using Microsoft.Data.Sqlite;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public sealed class SqliteReleaseCatalogService(
    SqliteRegistryStore sqliteStore) : IReleaseCatalogService
{
    public async Task<IReadOnlyList<LibraryCatalogEntryResponse>> GetLibrariesAsync(string? query, CancellationToken cancellationToken)
    {
        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);
        await using var command = CreateLibraryQuery(connection, query, null);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);

        var result = new List<LibraryCatalogEntryResponse>();
        while (await reader.ReadAsync(cancellationToken))
        {
            result.Add(MapLibrary(reader));
        }

        return result;
    }

    public async Task<LibraryCatalogEntryResponse?> GetLibraryAsync(long libraryId, CancellationToken cancellationToken)
    {
        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);
        await using var command = CreateLibraryQuery(connection, null, libraryId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);

        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return MapLibrary(reader);
    }

    public async Task<IReadOnlyList<ReleaseRecordResponse>> GetReleasesAsync(long? libraryId, string? status, CancellationToken cancellationToken)
    {
        await using var connection = await sqliteStore.OpenConnectionAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                r.release_id,
                r.release_num,
                n.uri,
                r.last_update_timestamp,
                r.export_data_link,
                CASE
                    WHEN r.release_id = (
                        SELECT lr.release_id
                        FROM "release" lr
                        WHERE lr.library_id = r.library_id
                        ORDER BY lr.release_id DESC
                        LIMIT 1
                    ) THEN 1
                    ELSE 0
                END
            FROM "release" r
            LEFT JOIN namespace n ON n.namespace_id = r.namespace_id
            WHERE ($library_id IS NULL OR r.library_id = $library_id)
              AND ($status IS NULL OR lower(r.state) = lower($status))
            ORDER BY r.release_id DESC
            """;
        command.Parameters.AddWithValue("$library_id", libraryId.HasValue ? libraryId.Value : DBNull.Value);
        command.Parameters.AddWithValue("$status", string.IsNullOrWhiteSpace(status) ? DBNull.Value : status);

        var rows = new List<ReleaseRow>();
        await using (var reader = await command.ExecuteReaderAsync(cancellationToken))
        {
            while (await reader.ReadAsync(cancellationToken))
            {
                rows.Add(new ReleaseRow(
                    reader.GetInt64(0),
                    reader.GetString(1),
                    reader.IsDBNull(2) ? null : reader.GetString(2),
                    ReadDateTimeOffset(reader, 3) ?? DateTimeOffset.MinValue,
                    reader.IsDBNull(4) ? string.Empty : reader.GetString(4),
                    reader.GetInt64(5) == 1));
            }
        }

        var dependenciesByReleaseId = await LoadDependenciesAsync(connection, rows.Select(row => row.ReleaseId).ToArray(), cancellationToken);

        return rows.Select(row =>
        {
            var dependencies = dependenciesByReleaseId.TryGetValue(row.ReleaseId, out var value)
                ? value
                : [];

            return new ReleaseRecordResponse(
                ReleaseId: row.ReleaseId,
                ReleaseNum: string.IsNullOrWhiteSpace(row.ReleaseNum) ? "Unnumbered" : row.ReleaseNum,
                NamespaceUri: row.NamespaceUri,
                LastUpdatedAtUtc: row.LastUpdatedAtUtc,
                ExportFileName: CreateExportFileName(row.ReleaseNum),
                ExportUrl: string.IsNullOrWhiteSpace(row.ExportDataLink)
                    ? CreateDefaultExportDataLink(row.ReleaseId)
                    : row.ExportDataLink,
                IsLatestRelease: row.IsLatestRelease,
                Dependencies: dependencies);
        }).ToArray();
    }

    private async Task<Dictionary<long, List<ReleaseDependencyResponse>>> LoadDependenciesAsync(
        SqliteConnection connection,
        IReadOnlyList<long> releaseIds,
        CancellationToken cancellationToken)
    {
        if (releaseIds.Count == 0)
        {
            return [];
        }

        await using var command = connection.CreateCommand();
        var parameterNames = new List<string>(releaseIds.Count);
        for (var index = 0; index < releaseIds.Count; index++)
        {
            var parameterName = $"$release_id_{index}";
            parameterNames.Add(parameterName);
            command.Parameters.AddWithValue(parameterName, releaseIds[index]);
        }

        command.CommandText =
            $"""
             SELECT
                 rd.release_id,
                 dr.release_id,
                 dl.library_id,
                 dl.name,
                 dr.release_num
             FROM release_dep rd
             JOIN "release" dr ON dr.release_id = rd.depend_on_release_id
             JOIN library dl ON dl.library_id = dr.library_id
             WHERE rd.release_id IN ({string.Join(", ", parameterNames)})
             ORDER BY rd.release_id, dr.release_id DESC
             """;

        var result = new Dictionary<long, List<ReleaseDependencyResponse>>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            var releaseId = reader.GetInt64(0);
            if (!result.TryGetValue(releaseId, out var dependencies))
            {
                dependencies = [];
                result[releaseId] = dependencies;
            }

            dependencies.Add(new ReleaseDependencyResponse(
                ReleaseId: reader.GetInt64(1),
                LibraryId: reader.GetInt64(2),
                LibraryName: reader.GetString(3),
                ReleaseNum: reader.GetString(4)));
        }

        return result;
    }

    private SqliteCommand CreateLibraryQuery(SqliteConnection connection, string? query, long? libraryId)
    {
        var command = connection.CreateCommand();
        command.CommandText =
            """
            SELECT
                l.library_id,
                l.name,
                l.organization,
                l.link,
                l.domain,
                l.description,
                (SELECT COUNT(*) FROM "release" r WHERE r.library_id = l.library_id),
                (SELECT r.release_num FROM "release" r WHERE r.library_id = l.library_id ORDER BY r.release_id DESC LIMIT 1),
                (SELECT r.last_update_timestamp FROM "release" r WHERE r.library_id = l.library_id ORDER BY r.release_id DESC LIMIT 1)
            FROM library l
            WHERE ($library_id IS NULL OR l.library_id = $library_id)
              AND (
                  $query IS NULL
                  OR lower(l.name) LIKE '%' || lower($query) || '%'
                  OR lower(l.organization) LIKE '%' || lower($query) || '%'
                  OR lower(l.domain) LIKE '%' || lower($query) || '%'
              )
            ORDER BY l.name ASC
            """;
        command.Parameters.AddWithValue("$library_id", libraryId.HasValue ? libraryId.Value : DBNull.Value);
        command.Parameters.AddWithValue("$query", string.IsNullOrWhiteSpace(query) ? DBNull.Value : query.Trim());
        return command;
    }

    private static LibraryCatalogEntryResponse MapLibrary(SqliteDataReader reader)
    {
        return new LibraryCatalogEntryResponse(
            LibraryId: reader.GetInt64(0),
            Name: reader.GetString(1),
            Organization: reader.GetString(2),
            Link: reader.GetString(3),
            Domain: reader.GetString(4),
            Description: reader.GetString(5),
            ReleaseCount: reader.GetInt32(6),
            LatestReleaseNum: reader.IsDBNull(7) ? null : reader.GetString(7),
            LatestLastUpdatedAtUtc: ReadDateTimeOffset(reader, 8));
    }

    private static DateTimeOffset? ReadDateTimeOffset(SqliteDataReader reader, int ordinal)
    {
        if (reader.IsDBNull(ordinal))
        {
            return null;
        }

        return DateTimeOffset.Parse(reader.GetString(ordinal), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
    }

    private static string CreateExportFileName(string releaseNum)
    {
        var safeName = string.IsNullOrWhiteSpace(releaseNum)
            ? "release"
            : System.Text.RegularExpressions.Regex.Replace(releaseNum, "[^A-Za-z0-9._-]+", "_");

        return $"{safeName}.release-export.zip";
    }

    private static string CreateDefaultExportDataLink(long releaseId)
    {
        return $"/api/releases/{releaseId}/export";
    }

    private sealed record ReleaseRow(
        long ReleaseId,
        string ReleaseNum,
        string? NamespaceUri,
        DateTimeOffset LastUpdatedAtUtc,
        string ExportDataLink,
        bool IsLatestRelease);
}
