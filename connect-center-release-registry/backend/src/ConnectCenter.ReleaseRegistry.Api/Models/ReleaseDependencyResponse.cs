namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record ReleaseDependencyResponse(
    long ReleaseId,
    long LibraryId,
    string LibraryName,
    string ReleaseNum);
