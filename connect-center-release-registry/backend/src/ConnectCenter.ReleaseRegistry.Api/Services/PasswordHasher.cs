using System.Security.Cryptography;
using BCryptNet = BCrypt.Net.BCrypt;

namespace ConnectCenter.ReleaseRegistry.Api.Services;

public static class PasswordHasher
{
    private const string Prefix = "pbkdf2";
    private const int Iterations = 100_000;
    private const int SaltSize = 16;
    private const int KeySize = 32;

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(SaltSize);
        var hash = Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, KeySize);
        return $"{Prefix}${Iterations}${Convert.ToBase64String(salt)}${Convert.ToBase64String(hash)}";
    }

    public static bool Verify(string? storedPassword, string providedPassword)
    {
        if (string.IsNullOrWhiteSpace(storedPassword))
        {
            return false;
        }

        return DescribeFormat(storedPassword) switch
        {
            "pbkdf2" => VerifyPbkdf2(storedPassword, providedPassword),
            "bcrypt" => VerifyBcrypt(storedPassword, providedPassword),
            _ => storedPassword == providedPassword,
        };
    }

    public static bool ShouldUpgradeHash(string? storedPassword)
    {
        return DescribeFormat(storedPassword) == "plain-or-unknown";
    }

    public static bool IsManagedHash(string? storedPassword)
    {
        return DescribeFormat(storedPassword) is "pbkdf2" or "bcrypt";
    }

    public static string DescribeFormat(string? storedPassword)
    {
        if (string.IsNullOrWhiteSpace(storedPassword))
        {
            return "empty";
        }

        if (storedPassword.StartsWith($"{Prefix}$", StringComparison.Ordinal))
        {
            return "pbkdf2";
        }

        if (storedPassword.StartsWith("$2a$", StringComparison.Ordinal)
            || storedPassword.StartsWith("$2b$", StringComparison.Ordinal)
            || storedPassword.StartsWith("$2y$", StringComparison.Ordinal))
        {
            return "bcrypt";
        }

        return "plain-or-unknown";
    }

    private static bool VerifyPbkdf2(string storedPassword, string providedPassword)
    {
        var parts = storedPassword.Split('$', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 4 || !int.TryParse(parts[1], out var iterations))
        {
            return false;
        }

        var salt = Convert.FromBase64String(parts[2]);
        var expectedHash = Convert.FromBase64String(parts[3]);
        var actualHash = Rfc2898DeriveBytes.Pbkdf2(providedPassword, salt, iterations, HashAlgorithmName.SHA256, expectedHash.Length);
        return CryptographicOperations.FixedTimeEquals(expectedHash, actualHash);
    }

    private static bool VerifyBcrypt(string storedPassword, string providedPassword)
    {
        try
        {
            return BCryptNet.Verify(providedPassword, storedPassword);
        }
        catch
        {
            return false;
        }
    }
}
