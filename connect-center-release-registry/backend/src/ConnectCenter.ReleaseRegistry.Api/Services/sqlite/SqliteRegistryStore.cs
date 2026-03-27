using ConnectCenter.ReleaseRegistry.Api.Options;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public sealed class SqliteRegistryStore
{
    private readonly string _databasePath;

    public SqliteRegistryStore(IOptions<SqliteRegistryOptions> options, IHostEnvironment environment)
    {
        var configuredPath = options.Value.DataSource;
        _databasePath = Path.IsPathRooted(configuredPath)
            ? configuredPath
            : Path.GetFullPath(Path.Combine(environment.ContentRootPath, configuredPath));
    }

    public string DatabasePath => _databasePath;

    public SqliteConnection CreateConnection()
    {
        return new SqliteConnection($"Data Source={_databasePath};Mode=ReadWriteCreate;Cache=Shared");
    }

    public async Task<SqliteConnection> OpenConnectionAsync(CancellationToken cancellationToken)
    {
        var directory = Path.GetDirectoryName(_databasePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var connection = CreateConnection();
        await connection.OpenAsync(cancellationToken);
        return connection;
    }
}
