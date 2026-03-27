namespace ConnectCenter.ReleaseRegistry.Api.Services;

public sealed class ReleaseRegistryStartupSyncService(
    ReleaseRegistrySyncService syncService,
    ILogger<ReleaseRegistryStartupSyncService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        logger.LogInformation("Refreshing SQLite release registry snapshot.");
        await syncService.SyncAsync(cancellationToken);
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        return Task.CompletedTask;
    }
}
