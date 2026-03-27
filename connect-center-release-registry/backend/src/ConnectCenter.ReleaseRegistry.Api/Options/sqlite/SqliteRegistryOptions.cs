namespace ConnectCenter.ReleaseRegistry.Api.Options;

public sealed class SqliteRegistryOptions
{
    public string DataSource { get; set; } = "data/release-registry.db";
}
