package de.quadrixyt.qavlen.javaapi;

import de.quadrixyt.qavlen.updater.SemanticVersion;
import de.quadrixyt.qavlen.updater.UpdateChannel;
import de.quadrixyt.qavlen.updater.UpdateChecker;
import de.quadrixyt.qavlen.updater.UpdateOptions;
import de.quadrixyt.qavlen.updater.UpdateResult;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterJavaTest {
    @Test
    void createsCheckersWithAndWithoutOptions() {
        UpdateOptions options = UpdateOptions.builder()
            .channel(UpdateChannel.BETA)
            .minecraftVersion("26.3")
            .platform("paper")
            .cacheDuration(Duration.ofMinutes(10))
            .timeout(Duration.ofSeconds(5))
            .userAgent("MyPlugin/1.0")
            .githubToken(() -> System.getenv("MY_PLUGIN_GITHUB_TOKEN"))
            .build();

        try (UpdateChecker modrinth = UpdateChecker.modrinth("my-maintenance-plugin", "1.0.0");
             UpdateChecker github = UpdateChecker.github("QuadrixYT18/MyPlugin", "1.0.0", options);
             UpdateChecker hangar = UpdateChecker.hangar("author/MyPlugin", "1.0.0", options)) {
            assertEquals("1.0.0", modrinth.getCurrentVersion());
            assertEquals(UpdateChannel.STABLE, modrinth.getOptions().getChannel());
            assertEquals("paper", github.getOptions().getPlatform());
            assertEquals(Duration.ofMinutes(10), hangar.getOptions().getCacheDuration());
            assertNull(modrinth.getLastResult());
        }
        assertThrows(IllegalArgumentException.class, () -> UpdateChecker.github("missing-owner", "1.0"));
    }

    @Test
    void resultsWorkWithPatternMatching() {
        UpdateResult result = new UpdateResult.Available("1.0.0", "1.1.0", UpdateChannel.STABLE,
            URI.create("https://modrinth.com/project/x/version/y"), null, "Notes", Instant.EPOCH);

        String message;
        if (result instanceof UpdateResult.Available update) {
            message = "Update " + update.getLatestVersion() + " at " + update.getReleaseUrl();
        } else if (result instanceof UpdateResult.UpToDate) {
            message = "Up to date";
        } else if (result instanceof UpdateResult.Failed failed) {
            message = "Failed: " + failed.getReason();
        } else {
            message = "unknown";
        }

        assertEquals("Update 1.1.0 at https://modrinth.com/project/x/version/y", message);
        assertEquals("1.0.0", result.getCurrentVersion());
        UpdateResult.Failed failed = new UpdateResult.Failed("1.0.0", "offline");
        assertNull(failed.getCause());
        assertNull(failed.getRetryAfter());
        assertNull(new UpdateResult.UpToDate("1.0.0", null).getLatestVersion());
    }

    @Test
    void comparesVersions() {
        assertEquals(1, SemanticVersion.compare("1.10.0", "1.9.0"));
        assertTrue(SemanticVersion.compare("1.2.0-beta.1", "1.2.0") < 0);
        assertNull(SemanticVersion.compare("latest", "1.0.0"));
        SemanticVersion version = SemanticVersion.parse("v2.0.0-rc.1");
        assertTrue(version.isPrerelease());
        assertEquals(UpdateChannel.BETA, version.getChannel());
        assertTrue(UpdateChannel.ALPHA.includes(UpdateChannel.BETA));
        assertFalse(UpdateChannel.STABLE.includes(UpdateChannel.ALPHA));
    }
}
