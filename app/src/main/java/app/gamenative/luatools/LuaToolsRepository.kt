package app.gamenative.luatools

import app.gamenative.data.DepotInfo
import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.LibraryCapsuleInfo
import app.gamenative.data.LibraryHeroInfo
import app.gamenative.data.LibraryLogoInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamLicense
import app.gamenative.enums.AppType
import app.gamenative.enums.Language
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.service.SteamService
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPaymentMethod
import java.io.File
import java.util.Date
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Orchestrates "add a non-owned game via LuaTools": fetch + parse a manifest,
 * persist the depot keys/app token, materialise a [SteamApp] with [DepotInfo]
 * so GameNative's normal install pipeline can see it, and pre-place any bundled
 * `.manifest` files where the downloader expects them.
 *
 * The one piece this cannot finish on its own is handing the depot decryption
 * keys to JavaSteam's DepotDownloader (see [LuaDepotKeyProvider] + INTEGRATION.md).
 */
object LuaToolsRepository {

    private val client = LuaToolsClient()
    private const val DEFAULT_BRANCH = "public"

    // Long-lived scope so an in-flight "add" keeps running even if the UI that
    // started it goes away (user backs out of the game page).
    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    /**
     * Fire-and-forget [addGame] on the background scope. Survives the caller leaving
     * the screen; reports the outcome via a global snackbar and an optional UI callback.
     */
    fun addGameInBackground(appId: Int, gameName: String, onComplete: ((Boolean) -> Unit)? = null) {
        bgScope.launch {
            val ctx = SteamService.instance?.applicationContext
            val ok = addGame(appId).isSuccess
            val msg = if (ok) {
                ctx?.getString(app.gamenative.R.string.lt_added_snackbar, gameName)
            } else {
                ctx?.getString(app.gamenative.R.string.lt_add_failed_snackbar, gameName)
            }
            if (!msg.isNullOrBlank()) app.gamenative.ui.util.SnackbarManager.show(msg)
            onComplete?.let { cb -> withContext(Dispatchers.Main) { cb(ok) } }
        }
    }

    /**
     * Standard Steam library-asset filenames. Combined with GameNative's
     * `https://shared.steamstatic.com/store_item_assets/steam/apps/<id>/<file>`
     * URL scheme these resolve to the real cover/hero/logo for virtually every
     * Steam app, so a manually-added title looks like any other.
     */
    private val STANDARD_ASSETS = LibraryAssetsInfo(
        libraryCapsule = LibraryCapsuleInfo(
            image = mapOf(Language.english to "library_600x900.jpg"),
            image2x = mapOf(Language.english to "library_600x900_2x.jpg"),
        ),
        libraryHero = LibraryHeroInfo(
            image = mapOf(Language.english to "library_hero.jpg"),
            image2x = mapOf(Language.english to "library_hero_2x.jpg"),
        ),
        libraryLogo = LibraryLogoInfo(
            image = mapOf(Language.english to "logo.png"),
            image2x = mapOf(Language.english to "logo.png"),
        ),
    )

    data class AddResult(
        val appId: Int,
        val depotKeyCount: Int,
        val manifestIdCount: Int,
        val manifestFilesWritten: Int,
        val hasAppToken: Boolean,
    )

    /**
     * Adds [appId] to the library from the configured LuaTools sources.
     * Returns failure when no source has the game or the DB isn't ready.
     */
    suspend fun addGame(appId: Int): Result<AddResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(appId > 0) { "Invalid appId" }
            val parsed = client.fetchManifest(appId)
                ?: throw IllegalStateException("No LuaTools source has a manifest for $appId")

            // 1) persist keys + token (survive restarts, feed the key provider)
            LuaToolsPrefs.putDepotKeys(parsed.depotKeys)
            LuaDepotKeyProvider.putAll(parsed.depotKeys)
            parsed.appToken?.let { LuaToolsPrefs.putAppToken(appId, it) }
            // remember this is a LuaTools app so the licence sync doesn't prune it
            LuaToolsPrefs.addAddedAppId(appId)

            // 2) build DepotInfo for every depot that has a key and/or a manifest id
            val depots = LinkedHashMap<Int, DepotInfo>()
            (parsed.depotKeys.keys + parsed.manifestGids.keys).forEach { depotId ->
                val gid = parsed.manifestGids[depotId]
                val manifests = if (gid != null) {
                    // Real install/download sizes come from the bundled manifest; the
                    // .lua-declared size is only the manifest blob size (a few KB), which
                    // makes the progress bar and "game size" read wildly wrong.
                    val (installSize, downloadSize) = manifestSizesFor(parsed, depotId, gid)
                    mapOf(
                        DEFAULT_BRANCH to ManifestInfo(
                            name = DEFAULT_BRANCH, gid = gid, size = installSize, download = downloadSize,
                        ),
                    )
                } else {
                    emptyMap()
                }
                depots[depotId] = DepotInfo(
                    depotId = depotId,
                    dlcAppId = SteamService.INVALID_APP_ID,
                    depotFromApp = appId,
                    sharedInstall = false,
                    osList = EnumSet.of(OS.windows),
                    osArch = OSArch.Arch64,
                    manifests = manifests,
                    encryptedManifests = emptyMap(),
                )
            }

            // 3) upsert a SteamApp so the game appears as installable
            val ownerId = SteamService.instance?.steamClient?.steamID?.accountID?.toInt()
            val existing = SteamService.getAppInfoOf(appId)
            val pkgId = existing?.packageId?.takeIf { it != SteamService.INVALID_PKG_ID } ?: appId
            val details = client.fetchAppDetails(appId)
            val app = (existing ?: SteamApp(id = appId)).copy(
                id = appId,
                name = details?.name?.takeIf { it.isNotBlank() }
                    ?: existing?.name?.takeIf { it.isNotBlank() }
                    ?: "App $appId",
                type = existing?.type?.takeIf { it != AppType.invalid } ?: AppType.game,
                receivedPICS = true,
                osList = EnumSet.of(OS.windows),
                depots = (existing?.depots ?: emptyMap()) + depots,
                packageId = pkgId,
                ownerAccountId = ownerId?.let { listOf(it) } ?: existing?.ownerAccountId ?: emptyList(),
                // real Steam metadata (developer / publisher / release date)
                developer = details?.developer?.takeIf { it.isNotBlank() }
                    ?: existing?.developer?.takeIf { it.isNotBlank() } ?: "",
                publisher = details?.publisher?.takeIf { it.isNotBlank() }
                    ?: existing?.publisher?.takeIf { it.isNotBlank() } ?: "",
                releaseDate = details?.releaseDateUnix?.takeIf { it > 0 }
                    ?: existing?.releaseDate?.takeIf { it > 0 } ?: 0L,
                // real Steam cover/hero/logo via the standard asset filenames
                libraryAssets = existing?.libraryAssets?.takeIf { it != LibraryAssetsInfo() } ?: STANDARD_ASSETS,
            )
            val appDao = SteamService.instance?.appDao
                ?: throw IllegalStateException("SteamService not ready (no DB) — sign in first")
            appDao.insert(app)

            // 3b) insert a synthetic, non-expired licence. The library only lists
            //     apps that have a matching steam_license row whose packageId equals
            //     the app's package_id and whose flags don't have the Expired bit
            //     (see SteamAppDao.OWNED_APPS_WHERE). Without this the game is hidden.
            val now = Date()
            SteamService.instance?.licenseDao?.insertAll(
                listOf(
                    SteamLicense(
                        packageId = pkgId,
                        lastChangeNumber = 0,
                        timeCreated = now,
                        timeNextProcess = now,
                        minuteLimit = 0,
                        minutesUsed = 0,
                        paymentMethod = EPaymentMethod.None,
                        licenseFlags = EnumSet.noneOf(ELicenseFlags::class.java),
                        purchaseCode = "",
                        licenseType = ELicenseType.SinglePurchase,
                        territoryCode = 0,
                        accessToken = 0L,
                        ownerAccountId = ownerId?.let { listOf(it) } ?: emptyList(),
                        masterPackageID = SteamService.INVALID_PKG_ID,
                        appIds = listOf(appId),
                        depotIds = depots.keys.toList(),
                    ),
                ),
            )

            // 4) pre-place bundled .manifest files where DepotDownloader looks
            val written = writeManifestFiles(appId, parsed)

            Timber.i(
                "LuaTools: added $appId — ${parsed.depotKeys.size} key(s), " +
                    "${parsed.manifestGids.size} manifest id(s), $written manifest file(s)",
            )
            AddResult(
                appId = appId,
                depotKeyCount = parsed.depotKeys.size,
                manifestIdCount = parsed.manifestGids.size,
                manifestFilesWritten = written,
                hasAppToken = parsed.appToken != null,
            )
        }.onFailure { Timber.e(it, "LuaTools: addGame($appId) failed") }
    }

    /**
     * Real `(installSize, downloadSize)` for a depot, read from the bundled Steam
     * manifest (`totalUncompressedSize` / `totalCompressedSize`). Falls back to the
     * `.lua`-declared size — which is only the manifest blob size — when the bundled
     * manifest is missing or unparseable. `downloadSize` matches the compressed-byte
     * unit the progress counter reports, so the download bar tracks correctly.
     */
    private fun manifestSizesFor(parsed: ParsedManifest, depotId: Int, gid: Long): Pair<Long, Long> {
        val fallback = parsed.manifestSizes[depotId] ?: 0L
        val bytes = parsed.manifestFiles["${depotId}_${gid}.manifest"]
            ?: parsed.manifestFiles["${depotId}_${gid.toULong()}.manifest"]
            ?: parsed.manifestFiles.entries.firstOrNull {
                it.key.startsWith("${depotId}_") && it.key.endsWith(".manifest")
            }?.value
            ?: return fallback to fallback
        return runCatching {
            val m = `in`.dragonbra.javasteam.types.DepotManifest.deserialize(bytes)
            val install = m.totalUncompressedSize.takeIf { it > 0 } ?: fallback
            val download = m.totalCompressedSize.takeIf { it > 0 } ?: install
            install to download
        }.getOrElse { fallback to fallback }
    }

    /**
     * Name-independent private cache for the bundled `.manifest` bytes. The real
     * install directory (`getAppDirPath`) is derived from the game's display name
     * and its `.DepotDownloader` subdir is wiped by the install flow between "add"
     * and "download", so we cannot rely on files placed there at add time. This
     * cache is keyed only by appId and survives that churn; [stageManifestsInto]
     * copies from here into the real config dir immediately before the download.
     */
    private fun manifestCacheDir(appId: Int): File? {
        val base = SteamService.instance?.applicationContext?.filesDir ?: return null
        return File(base, "luatools_manifests/$appId")
    }

    /**
     * Caches the bundled `.manifest` files under [manifestCacheDir] (named
     * `<depotId>_<gid>.manifest`, matching DepotDownloader's lookup). Also makes a
     * best-effort placement into the current install dir. Returns the number cached.
     */
    private fun writeManifestFiles(appId: Int, parsed: ParsedManifest): Int {
        if (parsed.manifestFiles.isEmpty() || parsed.manifestGids.isEmpty()) return 0
        val cache = manifestCacheDir(appId) ?: return 0
        if (!cache.exists()) cache.mkdirs()

        fun bundledBytesFor(depotId: Int, gid: Long): ByteArray? {
            // bundled files are usually named "<depotId>_<gid>.manifest"
            parsed.manifestFiles["${depotId}_${gid}.manifest"]?.let { return it }
            parsed.manifestFiles["${depotId}_${gid.toULong()}.manifest"]?.let { return it }
            // fall back to any bundled manifest for this depot
            return parsed.manifestFiles.entries.firstOrNull {
                it.key.startsWith("${depotId}_") && it.key.endsWith(".manifest")
            }?.value
        }

        var count = 0
        parsed.manifestGids.forEach { (depotId, gid) ->
            val bytes = bundledBytesFor(depotId, gid) ?: return@forEach
            runCatching {
                File(cache, "${depotId}_${gid.toULong()}.manifest").writeBytes(bytes)
                count++
            }.onFailure { Timber.w(it, "LuaTools: failed to cache manifest for depot $depotId") }
        }
        // NB: do NOT stage into the install dir here — creating <appDir>/.DepotDownloader
        // before any download makes hasPartialInstall() treat the game as a resumable
        // partial download. Staging happens at download time (see SteamService.downloadApp).
        return count
    }

    /**
     * Copies the cached bundled manifests into `<appDirPath>/.DepotDownloader`
     * together with the SHA-1 `.sha` sidecar DepotDownloader verifies. Call this
     * immediately before starting the download so the files sit in the exact
     * config dir it reads — when present, DepotDownloader loads the manifest from
     * disk and skips the CDN request-code path Steam refuses for non-owned apps.
     * No-op (returns 0) for apps with no cached LuaTools manifests. Idempotent.
     */
    fun stageManifestsInto(appId: Int, appDirPath: String): Int {
        val cache = manifestCacheDir(appId) ?: return 0
        val sources = cache.listFiles { f -> f.isFile && f.name.endsWith(".manifest") } ?: return 0
        if (sources.isEmpty()) return 0
        val dir = File(appDirPath, ".DepotDownloader")
        if (!dir.exists()) dir.mkdirs()

        var count = 0
        sources.forEach { src ->
            runCatching {
                val bytes = src.readBytes()
                File(dir, src.name).writeBytes(bytes)
                val sha = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
                File(dir, "${src.name}.sha").writeBytes(sha)
                count++
            }.onFailure { Timber.w(it, "LuaTools: failed to stage ${src.name}") }
        }
        if (count > 0) Timber.i("LuaTools: staged $count manifest file(s) into ${dir.absolutePath}")
        return count
    }
}
