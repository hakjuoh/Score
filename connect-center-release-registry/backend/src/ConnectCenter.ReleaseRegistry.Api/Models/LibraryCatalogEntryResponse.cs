namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record LibraryCatalogEntryResponse(
    long LibraryId,
    string Name,
    string Organization,
    string Link,
    string Domain,
    string Description,
    int ReleaseCount,
    string? LatestReleaseNum,
    DateTimeOffset? LatestLastUpdatedAtUtc);
