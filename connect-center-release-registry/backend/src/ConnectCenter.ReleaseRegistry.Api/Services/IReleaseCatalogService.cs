using ConnectCenter.ReleaseRegistry.Api.Models;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public interface IReleaseCatalogService
{
    Task<IReadOnlyList<LibraryCatalogEntryResponse>> GetLibrariesAsync(string? query, CancellationToken cancellationToken);

    Task<LibraryCatalogEntryResponse?> GetLibraryAsync(long libraryId, CancellationToken cancellationToken);

    Task<IReadOnlyList<ReleaseRecordResponse>> GetReleasesAsync(long? libraryId, string? status, CancellationToken cancellationToken);
}
