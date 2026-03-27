using ConnectCenter.ReleaseRegistry.Api.Options;
using ConnectCenter.ReleaseRegistry.Api.Services;
using BCryptNet = BCrypt.Net.BCrypt;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;

namespace ConnectCenter.ReleaseRegistry.Api.Tests;

public sealed class SqliteAuthServiceTests : IDisposable
{
    private readonly string _rootPath;
    private readonly SqliteRegistryStore _store;
    private readonly SqliteAuthService _service;

    public SqliteAuthServiceTests()
    {
        _rootPath = Path.Combine(Path.GetTempPath(), $"cc-release-registry-auth-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_rootPath);

        _store = new SqliteRegistryStore(
            Microsoft.Extensions.Options.Options.Create(new SqliteRegistryOptions { DataSource = "data/release-registry.db" }),
            new TestHostEnvironment(_rootPath));
        _service = new SqliteAuthService(_store, NullLogger<SqliteAuthService>.Instance);

        InitializeDatabaseAsync().GetAwaiter().GetResult();
    }

    [Fact]
    public async Task AuthenticateAsync_ReturnsDefaultAdmin_WhenDatabaseIsFresh()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await SqliteAuthService.EnsureDefaultAdminUserAsync(connection, null, CancellationToken.None);

        var user = await _service.AuthenticateAsync("admin", "admin", CancellationToken.None);

        Assert.NotNull(user);
        Assert.Equal("admin", user!.Username);
        Assert.True(user.IsAdmin);
    }

    [Fact]
    public async Task UpdateProfileAsync_ChangesDisplayNameAndPassword_AndKeepsUsername()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await SqliteAuthService.EnsureDefaultAdminUserAsync(connection, null, CancellationToken.None);

        var initial = await _service.AuthenticateAsync("admin", "admin", CancellationToken.None);
        Assert.NotNull(initial);

        var result = await _service.UpdateProfileAsync(
            initial!.AppUserId,
            "Registry Admin",
            "admin",
            "secret-123",
            CancellationToken.None);

        Assert.True(result.Success);
        Assert.NotNull(result.User);
        Assert.Equal("admin", result.User!.Username);
        Assert.Equal("Registry Admin", result.User!.DisplayName);

        var relogged = await _service.AuthenticateAsync("admin", "secret-123", CancellationToken.None);
        Assert.NotNull(relogged);
        Assert.Equal(initial.AppUserId, relogged!.AppUserId);
        Assert.Equal("Registry Admin", relogged.DisplayName);
    }

    [Fact]
    public async Task UpdateProfileAsync_ChangesDisplayNameAndPassword_ForBcryptUser_AndKeepsUsername()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await using var insert = connection.CreateCommand();
        insert.CommandText =
            """
            INSERT INTO app_user (
                app_user_id, login_id, password, name, organization, email,
                is_developer, is_admin, is_enabled
            ) VALUES (
                3, 'hakju.oh', $password, 'Hakju Oh', 'OAGi', NULL,
                1, 0, 1
            )
            """;
        insert.Parameters.AddWithValue("$password", BCryptNet.HashPassword("secret-123"));
        await insert.ExecuteNonQueryAsync(CancellationToken.None);

        var initial = await _service.AuthenticateAsync("hakju.oh", "secret-123", CancellationToken.None);
        Assert.NotNull(initial);

        var result = await _service.UpdateProfileAsync(
            initial!.AppUserId,
            "Hakju O.",
            "secret-123",
            "secret-456",
            CancellationToken.None);

        Assert.True(result.Success);
        Assert.NotNull(result.User);
        Assert.Equal("hakju.oh", result.User!.Username);
        Assert.Equal("Hakju O.", result.User.DisplayName);

        var relogged = await _service.AuthenticateAsync("hakju.oh", "secret-456", CancellationToken.None);
        Assert.NotNull(relogged);
        Assert.Equal(initial.AppUserId, relogged!.AppUserId);
        Assert.Equal("Hakju O.", relogged.DisplayName);
    }

    [Fact]
    public async Task EnsureDefaultAdminUserAsync_DoesNotInsertAdmin_WhenUsersAlreadyExist()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await using var command = connection.CreateCommand();
        command.CommandText =
            """
            INSERT INTO app_user (
                app_user_id, login_id, password, name, organization, email,
                is_developer, is_admin, is_enabled
            ) VALUES (
                0, 'sysadm', NULL, 'System', 'connectCenter', NULL,
                1, 1, 1
            )
            """;
        await command.ExecuteNonQueryAsync(CancellationToken.None);

        await SqliteAuthService.EnsureDefaultAdminUserAsync(connection, null, CancellationToken.None);

        await using var countCommand = connection.CreateCommand();
        countCommand.CommandText = "SELECT COUNT(*) FROM app_user";
        var appUserCount = Convert.ToInt32(await countCommand.ExecuteScalarAsync(CancellationToken.None));

        var user = await _service.AuthenticateAsync("admin", "admin", CancellationToken.None);

        Assert.Equal(1, appUserCount);
        Assert.Null(user);
    }

    public void Dispose()
    {
        if (Directory.Exists(_rootPath))
        {
            Directory.Delete(_rootPath, recursive: true);
        }
    }

    private async Task InitializeDatabaseAsync()
    {
        await using var connection = await _store.OpenConnectionAsync(CancellationToken.None);
        await SqliteRegistrySchema.CreateAsync(connection, CancellationToken.None);
    }

    private sealed class TestHostEnvironment(string contentRootPath) : IHostEnvironment
    {
        public string EnvironmentName { get; set; } = Environments.Development;

        public string ApplicationName { get; set; } = "ConnectCenter.ReleaseRegistry.Api.Tests";

        public string ContentRootPath { get; set; } = contentRootPath;

        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    }
}
