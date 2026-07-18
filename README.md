# Unchained

A fork of **[GameNative](https://github.com/utkarshdalal/GameNative) v1.1.0** (versionCode 19) that adds a workflow for installing Steam games from community manifest sources, plus a community fix installer and extra library indicators.

GameNative runs Windows games on Android in a Wine/Proton container, authenticating with Steam via [JavaSteam](https://github.com/Longi94/JavaSteam) and downloading depots with its bundled `DepotDownloader`. Unchained extends that pipeline so a title you don't own can be added, downloaded and patched the same way an owned one is.

It installs alongside stock GameNative — the application ID is `app.gamenative.unchained`, so official updates never overwrite or revert it.

---

## What it adds

### LuaTools manifest pipeline

The LuaTools manifest-source system (originally a Millennium/SteamTools plugin, later a Decky plugin) ported to run natively.

There is no Steam.exe here to load a SteamTools `config/stplug-in/<appid>.lua`, so the original plugin's loader mechanism has nothing to hook. Instead this reuses what those manifests actually contain — per-depot AES decryption keys, manifest GIDs and an optional app token — and feeds them into GameNative's existing downloader.

Adding a game by AppID fetches and parses its `.lua`, persists the depot keys, writes a `SteamApp` row with its `DepotInfo`/`ManifestInfo`, and pre-places the `.manifest` files. The title then appears in the library and installs with the normal Install button.

Bundled sources — Hubcap, Ryuu, TwentyTwo Cloud and Sushi — are individually toggleable. Sources whose URL contains a `<…key…>` placeholder prompt for an API key in settings.

### Community fix installer

Games added this way often need a fix to launch. The game page gains an **Install Fix** control listing every fix available for that title across Ryuu, LuaTools generic/online, Perondepot and Unsteam, each labelled with its source and a colour-coded badge (`online`, `bypass`, `tested`, `unstable`, …).

Installing one extracts it over the install directory and records any DLL overrides it implies, which are re-applied on every launch so they survive a container reset.

### Store-wide search

With LuaTools enabled, library search also queries the Steam store, so non-owned titles surface alongside your own. Selecting one opens a page offering to add it through the manifest pipeline.

### Extra indicators

- **Denuvo** — titles protected by Denuvo Anti-Tamper are badged in the library and noted on the game page, detected from Steam's declared DRM information.

### Per-game fixes

A registry of built-in compatibility fixes applied automatically at launch, keyed by store and app ID (`STEAM_*`, `GOG_*`, `EPIC_*`).

---

## Building

A normal Android Studio project — open it, let Gradle sync, and Run.

```bash
git clone --recurse-submodules <this repo>
```

The submodules ([libadrenotools](https://github.com/Pipetto-crypto/libadrenotools), [lsfg-vk-android](https://github.com/GameNative/lsfg-vk-android)) are required for the native build. If you already cloned without them:

```bash
git submodule update --init --recursive
```

**Requirements:** Android SDK 36, NDK `27.3.13750724`, JDK 17+. `minSdk` is 26.

APKs land in `app/build/outputs/apk/<flavor>/<buildType>/`.

### Build types

| Type | Notes |
|---|---|
| `debug` | Unminified, debuggable |
| `release` | Minified, debug-signed |
| `release-signed` / `release-gold` | Minified, signed with the `pluvia` config |

The signed variants read `app/keystores/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). That file and any keystore are gitignored — supply your own. Without it the signed variants can't be built; `debug` and `release` are unaffected.

The depot-key injection lives in a JavaSteam `DepotDownloader` fork. Changes there require rebuilding its jar; app-module changes only need a normal Run.

---

## Notes

Some source is deliberately withheld and will not be present:

- `app/src/main/cpp/steambootstrap/steam_bootstrap.c` — encodes internal details of Steam's proprietary client that Valve does not publish. See `THIRD_PARTY_NOTICES`.
- `app/src/main/cpp/third_party/SDL2/` — vendored headers, fetched at build time.

---

## Credits & license

Built on **[GameNative](https://github.com/utkarshdalal/GameNative)** (GPL-3.0). The Steam/manifest workflow is ported from **LuaTools**. Steam connectivity is provided by **[JavaSteam](https://github.com/Longi94/JavaSteam)** (MIT).

Licensed under **GPL-3.0** — see [`LICENSE`](LICENSE).

---

**Disclaimer:** This is software for running games. You are responsible for how you use it and for complying with the laws and terms of service that apply to you. Provided without warranty of any kind.
