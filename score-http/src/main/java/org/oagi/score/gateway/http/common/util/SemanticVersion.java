package org.oagi.score.gateway.http.common.util;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to parse and manipulate semantic version strings, preserving any non-numeric prefix.
 * <p>
 * Supported format: {@code <prefix><major>[.<minor>][.<patch>][-<preRelease>][+<buildMetadata>]}.
 * Examples: {@code v1.2.3}, {@code release-2.0.0-alpha.1+build.5}.
 */
public class SemanticVersion {

    private static final Pattern FULL_SEMVER_PATTERN = Pattern.compile(
            "^(.*?)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?" +        // major, minor, patch
                    "(?:-([0-9A-Za-z.-]+))?" +                          // pre-release
                    "(?:\\+([0-9A-Za-z.-]+))?$"                         // build metadata
    );

    private String prefix; // may be null when no prefix is present
    private int major;
    private Integer minor;
    private Integer patch;
    private String preRelease;
    private String buildMetadata;

    /**
     * Create a {@link SemanticVersion} from discrete components (without prefix).
     *
     * @param major         major version number (required)
     * @param minor         optional minor version number (nullable)
     * @param patch         optional patch version number (nullable)
     * @param preRelease    optional pre-release label (nullable)
     * @param buildMetadata optional build metadata (nullable)
     */
    public SemanticVersion(int major, Integer minor, Integer patch,
                           String preRelease, String buildMetadata) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
        this.buildMetadata = buildMetadata;
    }

    /**
     * Create a {@link SemanticVersion} by parsing the given version string.
     *
     * @param version version string to parse
     * @throws IllegalArgumentException if the string is not a valid semantic version
     */
    public SemanticVersion(String version) {
        Matcher matcher = FULL_SEMVER_PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + version);
        }

        this.prefix = matcher.group(1); // may be null
        this.major = Integer.parseInt(matcher.group(2));
        this.minor = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : null;
        this.patch = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : null;
        this.preRelease = matcher.group(5);
        this.buildMetadata = matcher.group(6);
    }

    /**
     * Whether the version contains a pre-release component (e.g., {@code -alpha}, {@code -rc.1}).
     *
     * @return true if pre-release is present, false otherwise
     */
    public boolean hasPreRelease() {
        return preRelease != null;
    }

    /**
     * Whether the version contains build metadata (e.g., {@code +build.7}).
     *
     * @return true if build metadata is present, false otherwise
     */
    public boolean hasBuildMetadata() {
        return buildMetadata != null;
    }

    /**
     * Create a new version incremented from this version without mutating this instance.
     * <ul>
     *     <li>If a pre-release exists and {@code promote} is true, promote it (alpha → beta → rc → release).</li>
     *     <li>If a pre-release exists and {@code promote} is false, increment its trailing numeric part or append {@code .1}.</li>
     *     <li>If there is no pre-release, bump patch if present, else minor if present, else major.</li>
     * </ul>
     *
     * @param promote whether to promote pre-release (alpha → beta → rc → release)
     * @return a new {@link SemanticVersion} instance reflecting the increment
     */
    public SemanticVersion increment(boolean promote) {
        String newPrefix = this.prefix;
        int newMajor = this.major;
        Integer newMinor = this.minor;
        Integer newPatch = this.patch;
        String newPreRelease = this.preRelease;
        String newBuildMetadata = this.buildMetadata;

        if (newPreRelease != null) {
            newPreRelease = promote ? promotePreRelease(newPreRelease) : incrementPreRelease(newPreRelease);
        } else {
            // No pre-release → bump numbers
            if (newPatch != null) {
                newPatch = newPatch + 1;
            } else if (newMinor != null) {
                newMinor = newMinor + 1;
            } else {
                newMajor = newMajor + 1;
            }
        }

        SemanticVersion next = new SemanticVersion(newMajor, newMinor, newPatch, newPreRelease, newBuildMetadata);
        next.setPrefix(newPrefix);
        return next;
    }

    /**
     * Set or clear the optional prefix that appears before the numeric version.
     *
     * @param prefix nullable prefix to set; pass null to remove any prefix
     * @return this instance for chaining
     */
    public SemanticVersion setPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    private String promotePreRelease(String pre) {
        if (pre.startsWith("alpha")) {
            return "beta";
        } else if (pre.startsWith("beta")) {
            return "rc";
        } else if (pre.startsWith("rc")) {
            return null; // release → drop pre-release
        }
        return pre; // unknown → leave unchanged
    }

    private String incrementPreRelease(String pre) {
        String[] parts = pre.split("\\.");
        String last = parts[parts.length - 1];

        if (last.matches("\\d+")) {
            int num = Integer.parseInt(last) + 1;
            parts[parts.length - 1] = String.valueOf(num);
        } else {
            // text → append ".1"
            parts = Arrays.copyOf(parts, parts.length + 1);
            parts[parts.length - 1] = "1";
        }

        return String.join(".", parts);
    }

    /**
     * Render the version back to a string, preserving original prefix and present components.
     *
     * @return semantic version string
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        sb.append(major);
        if (minor != null) {
            sb.append(".").append(minor);
        }
        if (patch != null) {
            sb.append(".").append(patch);
        }
        if (preRelease != null) {
            sb.append("-").append(preRelease);
        }
        if (buildMetadata != null) {
            sb.append("+").append(buildMetadata);
        }
        return sb.toString();
    }

}
