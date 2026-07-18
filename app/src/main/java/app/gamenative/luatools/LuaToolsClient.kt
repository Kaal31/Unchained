package app.gamenative.luatools

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Fetches a game's manifest archive from the configured LuaTools sources and
 * parses it. Ports the source-iteration + key-substitution + zip-validation
 * logic from the original plugin's downloads.py.
 */
class LuaToolsClient(
    private val http: OkHttpClient = OkHttpClient(),
) {
    /** Fill `<appid>` and any key placeholders; returns null if a required key is missing. */
    private fun buildUrl(template: String, appId: Int): String? {
        var url = template
        for (placeholder in ManifestSources.placeholdersIn(url)) {
            val key = LuaToolsPrefs.getApiKey(placeholder)
            if (key.isBlank()) return null // key-gated source with no key -> skip
            url = url.replace(placeholder, key)
        }
        return url.replace(ManifestSources.APPID_PLACEHOLDER, appId.toString())
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    /**
     * Try each enabled source in order and return the parsed manifest for the
     * first one that yields a valid zip containing a numeric `<n>.lua`.
     * Returns null when no source has the game.
     */
    fun fetchManifest(appId: Int): ParsedManifest? {
        for (source in LuaToolsPrefs.getSources().filter { it.enabled }) {
            val url = buildUrl(source.url, appId) ?: continue
            Timber.i("LuaTools: trying source '${source.name}' for $appId")
            val bytes = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                    .execute().use { resp ->
                        if (resp.code != source.successCode) return@use null
                        resp.body?.bytes()
                    }
            }.getOrNull() ?: continue

            if (!isZip(bytes)) {
                Timber.w("LuaTools: source '${source.name}' returned non-zip for $appId")
                continue
            }
            val parsed = runCatching { parseZip(appId, bytes) }.getOrNull()
            if (parsed != null && (parsed.depotKeys.isNotEmpty() || parsed.manifestGids.isNotEmpty())) {
                Timber.i("LuaTools: '${source.name}' provided ${parsed.depotKeys.size} depot key(s) for $appId")
                return parsed
            }
        }
        return null
    }

    /** Public store metadata for a game (no auth needed). */
    data class AppDetails(
        val name: String?,
        val developer: String?,
        val publisher: String?,
        val releaseDateUnix: Long,
    )

    /** Best-effort game metadata from Steam's public store API. */
    fun fetchAppDetails(appId: Int): AppDetails? = runCatching {
        val url = "https://store.steampowered.com/api/appdetails?appids=$appId&l=english"
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val entry = org.json.JSONObject(body).optJSONObject(appId.toString()) ?: return@use null
                if (!entry.optBoolean("success", false)) return@use null
                val data = entry.optJSONObject("data") ?: return@use null
                AppDetails(
                    name = data.optString("name").takeIf { it.isNotBlank() },
                    developer = data.optJSONArray("developers")?.optString(0)?.takeIf { it.isNotBlank() },
                    publisher = data.optJSONArray("publishers")?.optString(0)?.takeIf { it.isNotBlank() },
                    releaseDateUnix = parseReleaseDate(
                        data.optJSONObject("release_date")?.optString("date").orEmpty(),
                    ),
                )
            }
    }.getOrNull()

    /**
     * Returns the concatenated DRM-related notices Steam publishes for [appId]
     * (`drm_notice`, `legal_notice`, `ext_user_account_notice`). Used to detect
     * third-party DRM like Denuvo. Best-effort; null on failure.
     */
    fun fetchDrmText(appId: Int): String? = runCatching {
        val url = "https://store.steampowered.com/api/appdetails?appids=$appId&l=english"
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val entry = org.json.JSONObject(body).optJSONObject(appId.toString()) ?: return@use null
                if (!entry.optBoolean("success", false)) return@use null
                val data = entry.optJSONObject("data") ?: return@use null
                listOf("drm_notice", "legal_notice", "ext_user_account_notice")
                    .joinToString(" ") { data.optString(it) }
            }
    }.getOrNull()

    private fun parseReleaseDate(text: String): Long {
        if (text.isBlank()) return 0L
        val patterns = listOf("d MMM, yyyy", "MMM d, yyyy", "d MMMM yyyy", "MMMM d, yyyy", "MMM yyyy", "yyyy")
        for (pattern in patterns) {
            val parsed = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH)
                    .apply { isLenient = true }
                    .parse(text)?.time?.div(1000L)
            }.getOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return 0L
    }

    private fun parseZip(appId: Int, zipBytes: ByteArray): ParsedManifest? {
        var luaContent: String? = null
        var luaName: String? = null
        val manifestFiles = LinkedHashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val base = entry.name.substringAfterLast('/')
                if (!entry.isDirectory) {
                    val data = zis.readBytes()
                    when {
                        base.matches(Regex("""\d+\.lua""")) -> {
                            // prefer the exact <appid>.lua, otherwise keep the first numeric lua
                            if (base == "$appId.lua" || luaContent == null) {
                                luaContent = data.toString(Charsets.UTF_8)
                                luaName = base
                            }
                        }
                        base.endsWith(".manifest") -> manifestFiles[base] = data
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val content = luaContent ?: return null
        Timber.d("LuaTools: parsing $luaName (${manifestFiles.size} manifest file(s))")
        return LuaManifestParser.parse(appId, content, manifestFiles)
    }

    /** A user's Hubcap manifest quota, parsed from `/user/stats` (matches Accela's fields). */
    data class HubcapStats(
        val dailyLimit: Int,
        val dailyUsage: Int,
        val totalCalls: Int,
        val username: String,
        val expiresText: String,
        val active: Boolean,
        val resetText: String,
    ) {
        val remaining: Int get() = (dailyLimit - dailyUsage).coerceAtLeast(0)
    }

    /** Fetches the Hubcap daily manifest quota for [apiKey]; null on error/invalid key. */
    fun fetchHubcapStats(apiKey: String): HubcapStats? {
        if (apiKey.isBlank()) return null
        // Stats live on the Hubcap host; fall back to the legacy Morrenus host.
        for (base in listOf("https://hubcapmanifest.com/api/v1", "https://manifest.morrenus.xyz/api/v1")) {
            val parsed = runCatching {
                val url = "$base/user/stats"
                http.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Authorization", "Bearer $apiKey") // per Hubcap docs
                        .build(),
                ).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val json = org.json.JSONObject(resp.body?.string() ?: return@use null)
                        if (json.has("error")) return@use null
                        val limit = json.optInt("daily_limit", 100).let { if (it <= 0) 100 else it }
                        HubcapStats(
                            dailyLimit = limit,
                            dailyUsage = json.optInt("daily_usage", 0).coerceIn(0, limit),
                            totalCalls = json.optInt("api_key_usage_count", 0),
                            username = json.optString("username", ""),
                            expiresText = formatExpiry(json.optString("api_key_expires_at", "")),
                            active = json.optBoolean("can_make_requests", true),
                            resetText = nextDailyResetText(),
                        )
                    }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun formatExpiry(iso: String): String {
        if (iso.isBlank()) return "Never"
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val d = fmt.parse(iso.take(19))!!
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(d)
        }.getOrDefault(iso.take(10))
    }

    /** Daily quota resets at 00:00 UTC; return that instant formatted in local time. */
    private fun nextDailyResetText(): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(cal.time)
    }

    companion object {
        private const val USER_AGENT = "discord(dot)gg/luatools"
    }
}
