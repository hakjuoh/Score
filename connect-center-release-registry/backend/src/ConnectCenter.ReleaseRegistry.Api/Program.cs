using System.Security.Claims;
using System.Diagnostics;
using ConnectCenter.ReleaseRegistry.Api.Models;
using ConnectCenter.ReleaseRegistry.Api.Options;
using ConnectCenter.ReleaseRegistry.Api.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authorization;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddOpenApi();
builder.Services
    .AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme)
    .AddCookie(options =>
    {
        options.Cookie.Name = "cc_registry_auth";
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Events.OnRedirectToAccessDenied = context =>
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return Task.CompletedTask;
        };
        options.Events.OnRedirectToLogin = context =>
        {
            context.Response.StatusCode = StatusCodes.Status401Unauthorized;
            return Task.CompletedTask;
        };
    });
builder.Services.AddAuthorization();
builder.Services.Configure<SqliteRegistryOptions>(builder.Configuration.GetSection("Sqlite"));
builder.Services.Configure<MariaDbSourceOptions>(builder.Configuration.GetSection("MariaDb"));
builder.Services.AddSingleton<SqliteRegistryStore>();
builder.Services.AddSingleton<ReleaseRegistrySyncService>();
builder.Services.AddHostedService<ReleaseRegistryStartupSyncService>();
builder.Services.AddSingleton<IReleaseCatalogService, SqliteReleaseCatalogService>();
builder.Services.AddSingleton<IAuthService, SqliteAuthService>();
builder.Services.AddCors(options =>
{
    options.AddPolicy("frontend", policy =>
    {
        var origins = builder.Configuration.GetSection("Frontend:AllowedOrigins").Get<string[]>();

        if (origins is { Length: > 0 })
        {
            policy.WithOrigins(origins).AllowAnyHeader().AllowAnyMethod().AllowCredentials();
            return;
        }

        policy.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod();
    });
});

var app = builder.Build();
var requestLogger = app.Logger;

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.Use(async (context, next) =>
{
    var stopwatch = Stopwatch.StartNew();
    var username = context.User.Identity?.IsAuthenticated == true ? context.User.Identity?.Name : "anonymous";

    requestLogger.LogInformation(
        "HTTP {Method} {Path}{QueryString} started. TraceId={TraceId} User={Username}",
        context.Request.Method,
        context.Request.Path,
        context.Request.QueryString,
        context.TraceIdentifier,
        username);

    try
    {
        await next();

        requestLogger.LogInformation(
            "HTTP {Method} {Path}{QueryString} completed with {StatusCode} in {ElapsedMs}ms. TraceId={TraceId} User={Username}",
            context.Request.Method,
            context.Request.Path,
            context.Request.QueryString,
            context.Response.StatusCode,
            stopwatch.ElapsedMilliseconds,
            context.TraceIdentifier,
            username);
    }
    catch (Exception ex)
    {
        requestLogger.LogError(
            ex,
            "HTTP {Method} {Path}{QueryString} failed after {ElapsedMs}ms. TraceId={TraceId} User={Username}",
            context.Request.Method,
            context.Request.Path,
            context.Request.QueryString,
            stopwatch.ElapsedMilliseconds,
            context.TraceIdentifier,
            username);
        throw;
    }
});

app.UseCors("frontend");

if (!app.Environment.IsDevelopment())
{
    app.UseHttpsRedirection();
}

app.UseAuthentication();
app.UseAuthorization();

var api = app.MapGroup("/api");
var authorizedApi = api.MapGroup(string.Empty).RequireAuthorization();

api.MapGet("/health", (IConfiguration configuration) =>
{
    var response = new
    {
        status = "ok",
        service = "connect-center-release-registry-api",
        releaseSource = configuration["ReleaseRegistry:Source"],
        authenticationMode = configuration["Authentication:Mode"],
    };

    return Results.Ok(response);
})
.WithName("GetHealth");

api.MapGet("/libraries", async (string? q, IReleaseCatalogService service, CancellationToken cancellationToken) =>
{
    var libraries = await service.GetLibrariesAsync(q, cancellationToken);
    return Results.Ok(libraries);
})
.WithName("GetLibraries");

api.MapGet("/libraries/{libraryId:long}", async (long libraryId, IReleaseCatalogService service, CancellationToken cancellationToken) =>
{
    var library = await service.GetLibraryAsync(libraryId, cancellationToken);
    return library is null ? Results.NotFound() : Results.Ok(library);
})
.WithName("GetLibrary");

api.MapGet("/libraries/{libraryId:long}/releases", async (long libraryId, string? status, IReleaseCatalogService service, CancellationToken cancellationToken) =>
{
    var releases = await service.GetReleasesAsync(libraryId, status, cancellationToken);
    return Results.Ok(releases);
})
.WithName("GetLibraryReleases");

api.MapGet("/releases", async (long? libraryId, string? status, IReleaseCatalogService service, CancellationToken cancellationToken) =>
{
    var releases = await service.GetReleasesAsync(libraryId, status, cancellationToken);
    return Results.Ok(releases);
})
.WithName("GetReleases");

api.MapPost("/auth/login", async (
    LoginRequest request,
    HttpContext httpContext,
    IAuthService authService,
    ILogger<Program> logger,
    CancellationToken cancellationToken) =>
{
    var submittedUsername = request.Username?.Trim() ?? string.Empty;

    logger.LogInformation(
        "Login requested for username '{Username}'. TraceId={TraceId}",
        submittedUsername,
        httpContext.TraceIdentifier);

    var user = await authService.AuthenticateAsync(submittedUsername, request.Password ?? string.Empty, cancellationToken);
    if (user is null)
    {
        logger.LogWarning(
            "Login failed for username '{Username}'. TraceId={TraceId}",
            submittedUsername,
            httpContext.TraceIdentifier);
        return Results.Json(new { error = "Invalid username or password." }, statusCode: StatusCodes.Status401Unauthorized);
    }

    await SignInAsync(httpContext, user);
    logger.LogInformation(
        "Login succeeded for username '{Username}' (AppUserId={AppUserId}). TraceId={TraceId}",
        user.Username,
        user.AppUserId,
        httpContext.TraceIdentifier);
    return Results.Ok(user);
})
.WithName("Login");

authorizedApi.MapPost("/auth/logout", async (HttpContext httpContext) =>
{
    app.Logger.LogInformation(
        "Logout requested by '{Username}'. TraceId={TraceId}",
        httpContext.User.Identity?.Name ?? "anonymous",
        httpContext.TraceIdentifier);
    await httpContext.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
    return Results.NoContent();
})
.WithName("Logout");

authorizedApi.MapGet("/auth/me", async (
    HttpContext httpContext,
    IAuthService authService,
    CancellationToken cancellationToken) =>
{
    var appUserId = GetAppUserId(httpContext.User);
    if (appUserId is null)
    {
        return Results.Unauthorized();
    }

    var user = await authService.GetUserAsync(appUserId.Value, cancellationToken);
    return user is null ? Results.Unauthorized() : Results.Ok(user);
})
.WithName("GetCurrentUser");

authorizedApi.MapPatch("/auth/profile", async (
    UpdateProfileRequest request,
    HttpContext httpContext,
    IAuthService authService,
    CancellationToken cancellationToken) =>
{
    var appUserId = GetAppUserId(httpContext.User);
    if (appUserId is null)
    {
        return Results.Unauthorized();
    }

    var result = await authService.UpdateProfileAsync(
        appUserId.Value,
        request.DisplayName,
        request.CurrentPassword,
        request.NewPassword,
        cancellationToken);

    if (!result.Success || result.User is null)
    {
        return Results.BadRequest(new { error = result.Error ?? "Unable to update profile." });
    }

    await SignInAsync(httpContext, result.User);
    return Results.Ok(result.User);
})
.WithName("UpdateProfile");

app.Run();

static long? GetAppUserId(ClaimsPrincipal user)
{
    var value = user.FindFirstValue(ClaimTypes.NameIdentifier);
    return long.TryParse(value, out var appUserId) ? appUserId : null;
}

static async Task SignInAsync(HttpContext httpContext, AuthUserResponse user)
{
    var claims = new List<Claim>
    {
        new(ClaimTypes.NameIdentifier, user.AppUserId.ToString()),
        new(ClaimTypes.Name, user.Username),
        new("display_name", user.DisplayName),
    };

    if (user.IsAdmin)
    {
        claims.Add(new Claim(ClaimTypes.Role, "Admin"));
    }

    if (user.IsDeveloper)
    {
        claims.Add(new Claim("is_developer", "true"));
    }

    var identity = new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme);
    var principal = new ClaimsPrincipal(identity);
    await httpContext.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, principal);
}

public partial class Program;
