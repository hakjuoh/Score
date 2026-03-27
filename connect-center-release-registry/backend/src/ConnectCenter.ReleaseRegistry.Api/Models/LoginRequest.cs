namespace ConnectCenter.ReleaseRegistry.Api.Models;

public sealed record LoginRequest(
    string Username,
    string Password);
