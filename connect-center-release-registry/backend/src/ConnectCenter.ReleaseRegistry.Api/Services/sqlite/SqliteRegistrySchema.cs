using Microsoft.Data.Sqlite;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public static class SqliteRegistrySchema
{
    public static async Task CreateAsync(SqliteConnection connection, CancellationToken cancellationToken)
    {
        var command = connection.CreateCommand();
        command.CommandText =
            """
            PRAGMA journal_mode = DELETE;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS sync_info (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS app_user (
                app_user_id INTEGER PRIMARY KEY,
                login_id TEXT NOT NULL,
                password TEXT NULL,
                name TEXT NULL,
                organization TEXT NULL,
                email TEXT NULL,
                is_developer INTEGER NOT NULL,
                is_admin INTEGER NOT NULL,
                is_enabled INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS library (
                library_id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                organization TEXT NOT NULL,
                description TEXT NOT NULL,
                link TEXT NOT NULL,
                domain TEXT NOT NULL,
                state TEXT NOT NULL,
                is_read_only INTEGER NOT NULL,
                is_default INTEGER NOT NULL,
                created_by INTEGER NOT NULL,
                last_updated_by INTEGER NOT NULL,
                creation_timestamp TEXT NOT NULL,
                last_update_timestamp TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS namespace (
                namespace_id INTEGER PRIMARY KEY,
                library_id INTEGER NOT NULL,
                uri TEXT NOT NULL,
                prefix TEXT NULL,
                description TEXT NULL
            );

            CREATE TABLE IF NOT EXISTS "release" (
                release_id INTEGER PRIMARY KEY,
                library_id INTEGER NOT NULL,
                guid TEXT NOT NULL,
                release_num TEXT NOT NULL,
                release_note TEXT NULL,
                release_license TEXT NULL,
                namespace_id INTEGER NULL,
                created_by INTEGER NOT NULL,
                last_updated_by INTEGER NOT NULL,
                creation_timestamp TEXT NOT NULL,
                last_update_timestamp TEXT NOT NULL,
                state TEXT NOT NULL,
                prev_release_id INTEGER NULL,
                next_release_id INTEGER NULL,
                export_data_link TEXT NULL
            );

            CREATE TABLE IF NOT EXISTS release_dep (
                release_dep_id INTEGER PRIMARY KEY,
                release_id INTEGER NOT NULL,
                depend_on_release_id INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_library_name ON library(name);
            CREATE INDEX IF NOT EXISTS idx_release_library_id ON "release"(library_id);
            CREATE INDEX IF NOT EXISTS idx_release_state ON "release"(state);
            CREATE INDEX IF NOT EXISTS idx_release_dep_release_id ON release_dep(release_id);
            CREATE INDEX IF NOT EXISTS idx_release_dep_depend_on_release_id ON release_dep(depend_on_release_id);
            """;

        await command.ExecuteNonQueryAsync(cancellationToken);
        await EnsureColumnAsync(connection, "release", "export_data_link", "TEXT NULL", cancellationToken);
    }

    private static async Task EnsureColumnAsync(
        SqliteConnection connection,
        string tableName,
        string columnName,
        string columnDefinition,
        CancellationToken cancellationToken)
    {
        await using var pragma = connection.CreateCommand();
        pragma.CommandText = $"PRAGMA table_info(\"{tableName}\")";

        await using var reader = await pragma.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            if (string.Equals(reader.GetString(1), columnName, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
        }

        await reader.CloseAsync();

        await using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE \"{tableName}\" ADD COLUMN {columnName} {columnDefinition}";
        await alter.ExecuteNonQueryAsync(cancellationToken);
    }
}
