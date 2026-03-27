using BCryptNet = BCrypt.Net.BCrypt;
using ConnectCenter.ReleaseRegistry.Api.Services;

namespace ConnectCenter.ReleaseRegistry.Api.Tests;

public sealed class PasswordHasherTests
{
    [Fact]
    public void Verify_ReturnsTrue_ForBcryptHashes()
    {
        var hash = BCryptNet.HashPassword("secret-123");

        var verified = PasswordHasher.Verify(hash, "secret-123");

        Assert.True(verified);
        Assert.Equal("bcrypt", PasswordHasher.DescribeFormat(hash));
        Assert.False(PasswordHasher.ShouldUpgradeHash(hash));
        Assert.True(PasswordHasher.IsManagedHash(hash));
    }
}
