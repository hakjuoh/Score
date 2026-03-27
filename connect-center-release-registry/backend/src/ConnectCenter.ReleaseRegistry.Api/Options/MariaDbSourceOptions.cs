namespace ConnectCenter.ReleaseRegistry.Api.Options;

public sealed class MariaDbSourceOptions
{
    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 3306;

    public string Database { get; set; } = "oagi";

    public string User { get; set; } = "oagi";

    public string Password { get; set; } = "oagi";

    public string ToConnectionString()
    {
        return $"Server={Host};Port={Port};Database={Database};User ID={User};Password={Password};SslMode=None;Allow User Variables=True";
    }
}
