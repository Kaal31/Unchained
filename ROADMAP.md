# Roadmap

Nothing here is committed to a release. Items move up or drop off as they turn out to be practical or not.

---

## Cloud saves — [CloudRedirect](https://github.com/Selectively11/CloudRedirect) support

Games added through the manifest pipeline have no working Steam Cloud. Valve patched the old SteamTools approach (rewriting cloud requests to AppID 760, Steam Screenshots), which in any case shared one save namespace across every added game and didn't handle AutoCloud titles at all.

[CloudRedirect](https://github.com/Selectively11/CloudRedirect) by Selectively11 solves this properly: it intercepts Steam's cloud save RPC handlers and redirects reads and writes to Google Drive, OneDrive or a local folder, per game, including AutoCloud titles. Owned games continue to use real Steam Cloud untouched.

**What adoption here would involve.** CloudRedirect's existing implementations hook a running Steam client — a C++ DLL injected into Steam on Windows, a preloaded library on Linux. Unchained has no Steam client to hook; it talks to Steam over JavaSteam and runs the game in a Wine/Proton container. So this can't be ported directly.

The realistic shape is to reimplement the same idea at our own layer:

- Intercept the container's cloud-save reads and writes rather than Steam's RPC handlers
- Map each added AppID to its own prefix in the chosen cloud provider, matching CloudRedirect's per-game layout so saves stay interchangeable with the desktop tool
- Support Google Drive, OneDrive and a local/SAF folder, with the local option first since it needs no OAuth
- Handle AutoCloud-style path patterns, not just the explicit cloud file list

Open questions: where exactly to intercept in the container, how to reconcile conflicts without a Steam-side version number, and whether to reuse CloudRedirect's on-disk layout verbatim so a save written on desktop opens on Android.

---

## Denuvo-protected titles

Unchained already detects Denuvo Anti-Tamper from Steam's declared DRM information and badges those titles in the library. Two things would make that more useful:

**Warn before the download, not after.** Right now the badge is informational and easy to miss. Denuvo titles are frequently large, and someone can spend a 60 GB download before discovering the game won't start for them. Surfacing the DRM state at the point of adding or installing — with a clear explanation of what it means — saves that.

**Compatibility for owned Denuvo titles.** A Denuvo game you legitimately own still has to complete its activation handshake, and that path is untested inside the Wine/Proton container. Where it fails for container or networking reasons rather than licensing ones, those are ordinary compatibility bugs worth fixing so owned games run.

Defeating the protection itself is out of scope for this project.

---

## Smaller items

- **Fix source coverage** — more sources in the fix picker, and better badge accuracy on the ones already listed.
- **Manifest source health** — surface which sources are reachable and which are rate-limiting, instead of failing silently.
- **Fix rollback** — record what a fix changed so it can be cleanly undone, rather than reinstalling the game.
