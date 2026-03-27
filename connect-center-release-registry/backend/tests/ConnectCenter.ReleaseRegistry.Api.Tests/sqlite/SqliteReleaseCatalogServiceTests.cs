using ConnectCenter.ReleaseRegistry.Api.Options;
using ConnectCenter.ReleaseRegistry.Api.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Hosting;

namespace ConnectCenter.ReleaseRegistry.Api.Tests;

public class SqliteReleaseCatalogServiceTests : IDisposable
{
    private readonly string _rootPath;
    private readonly SqliteRegistryStore _store;
    private readonly SqliteReleaseCatalogService _service;

    public SqliteReleaseCatalogServiceTests()
    {
        _rootPath = Path.Combine(Path.GetTempPath(), $"cc-release-registry-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_rootPath);

        _store = new SqliteRegistryStore(
            Microsoft.Extensions.Options.Options.Create(new SqliteRegistryOptions { DataSource = "data/release-registry.db" }),
            new TestHostEnvironment(_rootPath));
        _service = new SqliteReleaseCatalogService(_store);

        SeedDatabaseAsync().GetAwaiter().GetResult();
    }

    [Fact]
    public async Task GetLibrariesAsync_ReturnsCatalogEntries()
    {
        var libraries = await _service.GetLibrariesAsync(null, CancellationToken.None);

        Assert.Equal(2, libraries.Count);
        Assert.Equal("ISO 15000-5", libraries[0].Name);
        Assert.Equal("connectSpec", libraries[1].Name);
    }

    [Fact]
    public async Task GetReleasesAsync_ReturnsLatestReleaseFirstWithDependencies()
    {
        var releases = await _service.GetReleasesAsync(3L, null, CancellationToken.None);

        Assert.Equal(2, releases.Count);
        Assert.True(releases[0].IsLatestRelease);
        Assert.Equal(69L, releases[0].ReleaseId);
        Assert.Equal("ISO 15000-5", releases[0].Dependencies[0].LibraryName);
        Assert.Equal("10.12.7.release-export.zip", releases[0].ExportFileName);
        Assert.Equal("s3://release-bucket/connectspec/10.12.7.release-export.zip", releases[0].ExportUrl);
        Assert.Equal("/api/releases/68/export", releases[1].ExportUrl);
    }

    public void Dispose()
    {
        if (Directory.Exists(_rootPath))
        {
            Directory.Delete(_rootPath, recursive: true);
        }
    }

    private async Task SeedDatabaseAsync()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await SqliteRegistrySchema.CreateAsync(connection, CancellationToken.None);

        await using var transaction = (SqliteTransaction) await connection.BeginTransactionAsync();
        await ExecuteAsync(connection, transaction,
            """
            INSERT INTO app_user (app_user_id, login_id, password, name, organization, email, is_developer, is_admin, is_enabled)
            VALUES
              (1, 'hakjuoh', NULL, 'Hakju Oh', 'OAGi', 'hakjuoh@example.org', 1, 1, 1),
              (2, 'release-bot', NULL, 'Release Bot', 'OAGi', 'bot@example.org', 0, 0, 1)
            """);

        await ExecuteAsync(connection, transaction,
            """
            INSERT INTO library (
                library_id, name, type, organization, description, link, domain, state,
                is_read_only, is_default, created_by, last_updated_by, creation_timestamp, last_update_timestamp
            ) VALUES
              (2, 'ISO 15000-5', 'Standard', 'International Organization for Standardization (ISO)',
               'ISO core components library.', '', 'International Trade', '', 0, 0, 1, 1,
               '2026-03-01T00:00:00.0000000+00:00', '2026-03-01T00:00:00.0000000+00:00'),
              (3, 'connectSpec', 'Standard', 'OAGi',
               'connectSpec provides standards and guidelines to enhance interoperability.',
               '', 'Enterprise Interoperability', 'Published', 0, 1, 1, 2,
               '2026-03-02T00:00:00.0000000+00:00', '2026-03-03T00:00:00.0000000+00:00')
            """);

        await ExecuteAsync(connection, transaction,
            """
            INSERT INTO namespace (namespace_id, library_id, uri, prefix, description)
            VALUES
              (500, 2, 'https://www.iso.org/15000-5', 'iso', 'ISO namespace'),
              (600, 3, 'https://www.oagi.org/connectspec', 'cs', 'connectSpec namespace')
            """);

        await ExecuteAsync(connection, transaction,
            """
            INSERT INTO "release" (
                release_id, library_id, guid, release_num, release_note, release_license,
                namespace_id, created_by, last_updated_by, creation_timestamp, last_update_timestamp,
                state, prev_release_id, next_release_id, export_data_link
            ) VALUES
              (56, 2, 'guid-56', '2014', 'ISO release', 'ISO license', 500, 1, 1,
               '2026-03-04T00:00:00.0000000+00:00', '2026-03-04T00:00:00.0000000+00:00', 'Published', NULL, NULL,
               'file:///var/releases/iso/2014.release-export.zip'),
              (54, 2, 'guid-54', '2013', 'Legacy ISO release', 'ISO license', 500, 1, 1,
               '2026-03-03T00:00:00.0000000+00:00', '2026-03-03T00:00:00.0000000+00:00', 'Published', NULL, 56,
               NULL),
              (68, 3, 'guid-68', '10.12.6', 'Prior connectSpec release', 'OAGi license', 600, 2, 2,
               '2026-03-05T00:00:00.0000000+00:00', '2026-03-05T00:00:00.0000000+00:00', 'Published', NULL, 69,
               NULL),
              (69, 3, 'guid-69', '10.12.7', 'Latest connectSpec release', 'OAGi license', 600, 2, 2,
               '2026-03-06T00:00:00.0000000+00:00', '2026-03-06T00:00:00.0000000+00:00', 'Published', 68, NULL,
               's3://release-bucket/connectspec/10.12.7.release-export.zip')
            """);

        await ExecuteAsync(connection, transaction,
            """
            INSERT INTO release_dep (release_dep_id, release_id, depend_on_release_id)
            VALUES
              (1, 68, 56),
              (2, 69, 56),
              (3, 69, 54)
            """);

        await transaction.CommitAsync();
    }

    private static async Task ExecuteAsync(SqliteConnection connection, SqliteTransaction transaction, string sql)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        await command.ExecuteNonQueryAsync();
    }

    private sealed class TestHostEnvironment(string contentRootPath) : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = Environments.Development;

        public string ApplicationName { get; set; } = "ConnectCenter.ReleaseRegistry.Api.Tests";

        public string ContentRootPath { get; set; } = contentRootPath;

        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    }
}
