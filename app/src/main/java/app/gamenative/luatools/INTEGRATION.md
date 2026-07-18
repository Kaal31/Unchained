# LuaTools integration for GameNative

Ports the LuaTools manifest-source system (originally a Millennium/SteamTools
plugin, then a Decky plugin) into GameNative so **non-owned** Steam games can be
added and installed through GameNative's own native download pipeline.

## Why this shape (and not SteamTools)

GameNative does **not** run the Windows Steam client. It authenticates with
JavaSteam (`SteamClient`) and downloads depots with JavaSteam's bundled
`in.dragonbra.javasteam.depotdownloader.DepotDownloader`, then runs the game
`.exe` in a Wine/Proton container. There is no Steam.exe to load a SteamTools
`config/stplug-in/<appid>.lua`, so the original plugin's loader mechanism has
nothing to hook here.

The **native** approach is therefore to reuse what LuaTools manifests actually
contain — per-depot AES **decryption keys**, **manifest GIDs**, and an optional
**app token** — and feed them into GameNative's existing downloader so a
non-owned game installs exactly like an owned one.

## What this package does (all self-contained, no app-wide changes)

| File | Role |
|---|---|
| `ManifestSource.kt` | Source model + bundled defaults (Morrenus/Ryuu/Sushi) + key-placeholder detection |
| `LuaToolsPrefs.kt` | DataStore prefs: sources, per-source API keys, depot-key cache, app tokens |
| `LuaManifestParser.kt` | Parses `addappid(depot,1,"key")`, `setManifestid(depot,"gid",size)`, `addtoken(app,"token")` |
| `LuaToolsClient.kt` | OkHttp: iterate sources, substitute `<appid>`/keys, download zip, extract `.lua` + `.manifest`, parse |
| `LuaDepotKeyProvider.kt` | The single lookup DepotDownloader should consult for a depot key |
| `LuaToolsRepository.kt` | `addGame(appId)`: fetch+parse → persist keys/token → upsert `SteamApp`+`DepotInfo` → pre-place `.manifest` files |
| `../ui/screen/settings/SettingsGroupLuaTools.kt` | Settings UI: enable, source toggles, optional API keys, "Add game by AppID" |

Wiring already applied:
- `PluviaApp.onCreate` → `LuaToolsPrefs.init(this)`
- `SettingsScreen` → new **LuaTools** section rendering `SettingsGroupLuaTools()`

After `addGame(appId)` the title appears in the library (a `SteamApp` row with
`DepotInfo`/`ManifestInfo` was inserted) and can be installed with the normal
Install button.

## The one remaining coupling — depot keys → DepotDownloader

`SteamService.downloadApp(...)` builds:

```kotlin
val depotDownloader = DepotDownloader(instance!!.steamClient!!, licenses, ...)
depotDownloader.add(AppItem(appId, installDirectory, depot = depotIds, branch, branchPassword))
```

For an **owned** app, DepotDownloader asks Steam for each depot's decryption key
(`SteamApps.getDepotDecryptionKey`). For a **non-owned** app that call fails, so
the download can't decrypt chunks. LuaTools already has the key
(`LuaDepotKeyProvider.hexKeyFor(depotId)` / `keyBytesFor(depotId)`); DepotDownloader
just needs to consult it as a fallback.

Two ways to close this, in order of preference:

1. **JavaSteam fork hook (recommended, cleanest).** In the JavaSteam
   `DepotDownloader` depot-key acquisition, before/after the Steam call, add:
   ```kotlin
   val key = steamKeyOrNull(depotId)
       ?: app.gamenative.luatools.LuaDepotKeyProvider.keyBytesFor(depotId)
   ```
   JavaSteam is pulled as a maven snapshot (`central.sonatype.com/.../maven-snapshots`),
   so this means a one-line change in the fork you build against. Ideally expose a
   `DepotKeyProvider` interface on the `DepotDownloader` constructor and pass
   `LuaDepotKeyProvider` from `SteamService.downloadApp`.

2. **Manifest side is already handled in-app.** GameNative loads manifests from
   `${appDir}/.DepotDownloader/${depotId}_${gid}.manifest` when present
   (see `SteamService.kt` ~line 1265). `LuaToolsRepository.writeManifestFiles`
   pre-places the `.manifest` binaries bundled in the LuaTools zip there, so a
   manifest-request-code (which also needs ownership) is not required. Ensure the
   bundled files are named `${depotId}_${gid}.manifest`.

### Also likely needed: a synthetic licence so the download starts

`downloadApp` bails if `getLicensesFromDb()` is empty and DepotDownloader may
skip depots the account has no `License` for. For a fully non-owned title you
will likely also insert a synthetic `SteamLicense` + `CachedLicense`
(`app.gamenative.data`) granting `appId`/`depotIds`. That requires constructing a
JavaSteam `License` (serialised via `LicenseSerializer`) with plausible enum
values — do this against a real JavaSteam build in Android Studio; it's the one
part that can't be written blind here. `LuaToolsRepository` marks where this goes.

## Status / caveats

- The parser, source client, prefs, key store, repository and settings UI are
  complete and match GameNative's data models (`SteamApp`, `DepotInfo`,
  `ManifestInfo`) and DAOs.
- Not compile-verified in this environment (no Android SDK/Gradle here). Build in
  Android Studio; expect to resolve the two coupling points above (depot-key
  provider hookup + synthetic licence construction).
- Legal/ToS considerations for adding non-owned content are the operator's
  responsibility.
