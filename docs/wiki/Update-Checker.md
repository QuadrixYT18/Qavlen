`qavlen-updater` checks whether a newer version of **your** plugin or application is published on Modrinth, GitHub Releases or Hangar. It does not depend on Paper, Velocity or JDA and works in any JVM application.

Nothing is downloaded or installed: a result only links to the release page and, where the provider has one, the file.

## Installation

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.QuadrixYT18.Qavlen:qavlen-updater:VERSION") }
```

The module uses `qavlen-network` for HTTP and JSON. Its public API only uses JDK types.

## Usage

```kotlin
val checker = UpdateChecker.modrinth("my-plugin", pluginMeta.version) {
    platform("paper")
    minecraftVersion("26.3")
}

checker.checkAsync().thenAccept { result ->
    when (result) {
        is UpdateResult.Available -> logger.info("Update ${result.latestVersion}: ${result.releaseUrl}")
        is UpdateResult.UpToDate -> logger.info("Up to date")
        is UpdateResult.Failed -> logger.warning("Update check failed: ${result.reason}")
    }
}

// in onDisable
checker.close()
```

```java
UpdateChecker checker = UpdateChecker.modrinth("my-plugin", getPluginMeta().getVersion(),
    UpdateOptions.builder().platform("paper").minecraftVersion("26.3").build());

checker.checkAsync().thenAccept(result -> {
    if (result instanceof UpdateResult.Available update) {
        getLogger().info("Update " + update.getLatestVersion() + ": " + update.getReleaseUrl());
    } else if (result instanceof UpdateResult.Failed failed) {
        getLogger().warning("Update check failed: " + failed.getReason());
    }
});
```

The installed version is a plain string. On Paper use `pluginMeta.version`, on Velocity the version from `@Plugin`, and elsewhere your build's version.

## Providers

| Provider | Factory | Project | Endpoint |
| --- | --- | --- | --- |
| Modrinth | `UpdateChecker.modrinth(projectId, version)` | slug (`my-plugin`) or id | `GET https://api.modrinth.com/v2/project/{id}/version` |
| GitHub Releases | `UpdateChecker.github(repository, version)` | `owner/repo` | `GET https://api.github.com/repos/{owner}/{repo}/releases` |
| Hangar | `UpdateChecker.hangar(project, version)` | `owner/slug` or slug | `GET https://hangar.papermc.io/api/v1/projects/{project}/versions` |

Each factory also takes `UpdateOptions` (Java) or a configuration block (Kotlin).

**Modrinth.**
- **Versions:** only listed versions count. `version_type` (`release`, `beta`, `alpha`) decides the channel.
- **Filters:** the platform and Minecraft version are sent as the `loaders` and `game_versions` filters and checked again on each version.
- **Links:** the release page is the version page on modrinth.com; the download is the primary file.
- **Release notes:** the version's changelog.

**GitHub Releases.**
- **Versions:** the tag (`v1.2.0`) is the version. Drafts are skipped; pre-releases count as betas.
- **Links:** the release page is the release on github.com. The download is a `.jar` asset, preferring one whose name contains the platform (`Plugin-Velocity.jar`). Without a `.jar` asset there is no download link.
- **Release notes:** the release body.
- **No Minecraft filter:** GitHub releases carry no Minecraft metadata, so `minecraftVersion` is ignored.
- **Rate limit:** GitHub allows 60 requests per hour and address. For more, or for private repositories, pass a token: `githubToken { System.getenv("MY_PLUGIN_GITHUB_TOKEN") }`. It is read for each request and never stored, logged or included in results.

**Hangar.**
- **Versions:** only public versions count. Versions are read page by page (25 per page, at most 4 pages) until one of the configured channel appears.
- **Channels:** they are named by the author. Names containing `alpha`, `snapshot`, `dev` or `nightly` count as alphas; names containing `beta`, `rc` or `pre`, and channels flagged unstable, count as betas; all others count as stable.
- **Platforms:** Hangar knows `PAPER`, `VELOCITY` and `WATERFALL`. The Minecraft version is checked against the version list Hangar keeps per platform; for Velocity those are Velocity versions such as `3.4`.
- **Project names:** with only a slug, the owner is looked up once to build links to version pages.

## Version comparison

`SemanticVersion` compares versions by their numbers, never as text, so `1.10.0` is newer than `1.9.0`:

- **Prefix and build metadata:** a leading `v` and build metadata after `+` are ignored. Missing numbers count as `0` (`1.2` equals `1.2.0`).
- **Pre-release markers:** `snapshot`, `dev`, `nightly` < `alpha` (`a`) < `beta` (`b`) < `pre`, `preview` < `rc`, `cr` < release. A number may follow each marker: `1.2.0-SNAPSHOT` < `1.2.0-beta.1` < `1.2.0-beta.2` < `1.2.0-rc.1` < `1.2.0`.
- **Other labels:** platform suffixes such as `5.5.71-velocity` or `2.0.0-paper` do not change the order. `5.5.71-velocity` equals `5.5.71`.
- **Not comparable:** versions that do not start with a number, such as `latest` or a commit hash.

When the installed or the newest published version cannot be compared, the check reports `UpdateResult.Failed` with both versions in the reason, never `UpToDate`. If both texts are identical, it is up to date.

```kotlin
SemanticVersion.compare("1.10.0", "1.9.0")   // 1
SemanticVersion.compare("1.2.0-rc.1", "1.2.0") // negative
SemanticVersion.compare("latest", "1.0.0")   // null
```

## Release channels and compatibility

| Option | Default | Effect |
| --- | --- | --- |
| `channel(UpdateChannel.STABLE)` | `STABLE` | `BETA` adds betas and release candidates, `ALPHA` adds alphas and snapshots. A version whose name marks a pre-release (`2.0.0-beta.1`) counts as one even if the provider calls it a release. |
| `platform("paper")` | any | `paper`, `purpur`, `folia`, `spigot`, `bukkit`, `velocity`, `waterfall` or `bungeecord`. Paper also accepts versions for Spigot and Bukkit, Purpur those for Paper, Waterfall those for BungeeCord. |
| `minecraftVersion("26.3")` | any | Only versions that list exactly this version. |
| `cacheDuration(Duration)` | 30 minutes | How long a successful result is reused. |
| `timeout(Duration)` | 10 seconds | Per request. |
| `userAgent(String)` | names Qavlen and the project | Identify your plugin, as Modrinth asks: `MyPlugin/1.0 (contact@example.com)`. |

The newest version is the highest comparable version that passes the filters. If none passes, the result is `UpToDate` with `latestVersion = null`.

## Asynchronous checks

- **Non-blocking:** `checkAsync()` never blocks. Requests run on the HTTP client's threads, and so do callbacks; switch to the server thread before touching players or worlds. `check()` waits for the result and must not run on a server's main thread.
- **Shared requests:** concurrent checks share one request. Cancelling a returned future stops only that caller's wait.
- **No exceptions:** the future never completes exceptionally. Every problem is an `UpdateResult.Failed`.
- **Closing:** call `close()` when the plugin is disabled.

## Caching and rate limits

- **Result cache:** a successful result is reused for `cacheDuration`, so running a command often does not send requests. `invalidate()` forgets it.
- **Revalidation:** responses with an `ETag` (GitHub, Hangar) are requested again with `If-None-Match`. An unchanged list comes back as `304 Not Modified` and is reused.
- **Rate limits:** a `429`, or GitHub's `403` without remaining requests, becomes `Failed` with `retryAfter`, read from `Retry-After` or `X-RateLimit-Reset`. Until then the checker sends no request and returns that failure. Waits are capped at one day.
- **Retries:** server errors `502`, `503` and `504` are retried once. Other failures are not retried and not cached.

## Results and errors

| Result | Meaning |
| --- | --- |
| `Available(currentVersion, latestVersion, channel, releaseUrl, downloadUrl, releaseNotes, publishedAt)` | A newer suitable version exists. `releaseUrl` is the page for people; `downloadUrl` is the file, or `null`. |
| `UpToDate(currentVersion, latestVersion)` | The installed version is the newest, or newer (for example a development build). |
| `Failed(currentVersion, reason, cause, retryAfter)` | Network error, timeout, unknown project (`404`), rejected token, rate limit, unexpected response or versions that cannot be compared. |

Network errors are never reported as up to date. Reasons name the provider and project but never contain headers or tokens.

## Security

- **Official providers only:** requests go to the official API hosts over HTTPS. Redirects are only followed within the same host, at most three times.
- **Bounded responses:** responses larger than 8 MiB are rejected. Links in results are `https` URLs or absent.
- **Untrusted release notes:** they are third-party text, often Markdown. Show them as plain text and never parse them as MiniMessage, HTML or commands.
- **No installation:** nothing is downloaded, executed or replaced.
