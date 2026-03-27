namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record ReleaseRecordResponse(
    long ReleaseId,
    string ReleaseNum,
    string? NamespaceUri,
    DateTimeOffset LastUpdatedAtUtc,
    string ExportFileName,
    string ExportUrl,
    bool IsLatestRelease,
    IReadOnlyList<ReleaseDependencyResponse> Dependencies);
