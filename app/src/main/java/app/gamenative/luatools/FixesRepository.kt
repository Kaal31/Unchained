package app.gamenative.luatools

import app.gamenative.mods.ModArchiveExtractor
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Aggregates game "fixes" (crack/bypass and online‑play patches) from several
 * community sources and exposes them as a per‑game options list:
 *
 *  - **Ryuu** (`generator.ryuu.lol/fixes`) — the richest per‑appid source; games
 *    can have several entries, each badged (bypass / online / tested). Looked up
 *    from a bundled index (`assets/luatools/ryuu_index.json`).
 *  - **LuaTools index** (`index.luatools.work`) — per‑appid generic bypass
 *    (`GameBypasses/{appid}.zip`) and online fix (`OnlineFix1/{appid}.zip`).
 *  - **Perondepot** (`api.perondepot.xyz/all`) — online fixes matched by game
 *    name (RAR archives; extracted via the shared libarchive extractor).
 *  - **Unsteam** — a universal all‑in‑one emulator, always offered.
 *
 * Applying a fix downloads its archive and extracts it into the game's install dir.
 */
object FixesRepository {

    private const val FIXES_INDEX_URL = "https://index.luatools.work/fixes-index.json"
    private const val GENERIC_FIX_URL = "https://files.luatools.work/GameBypasses/%d.zip"
    private const val ONLINE_FIX_URL = "https://files.luatools.work/OnlineFix1/%d.zip"
    private const val UNSTEAM_AIO_URL =
        "https://github.com/madoiscool/lt_api_links/releases/download/unsteam/Win64.zip"
    private const val RYUU_BASE = "https://generator.ryuu.lol/fixes/"
    private const val PERONDEPOT_BASE = "http://api.perondepot.xyz/all/"

    enum class Kind { GENERIC, ONLINE, UNSTEAM, RYUU, PERONDEPOT }

    /** One selectable fix for a game. */
    data class FixOption(
        val title: String,
        /** Where it comes from — shown as a chip (e.g. "Ryuu", "Perondepot"). */
        val source: String,
        /** Short tag — shown as a coloured pill (e.g. "online", "bypass", "tested"). */
        val badge: String,
        val url: String,
        val kind: Kind,
    )

    /** Outcome of installing a fix: files written + any DLL overrides applied. */
    data class FixResult(val files: Int, val dllOverrides: List<String>)

    private val http = OkHttpClient()
    @Volatile private var luaGeneric: Set<String>? = null
    @Volatile private var luaOnline: Set<String>? = null
    @Volatile private var ryuuIndex: Map<String, List<Pair<String, String>>>? = null // appid -> [(file, badge)]

    /** Every available fix for [appId] / [gameName], across all sources. Network; off main thread. */
    suspend fun listFixes(appId: Int, gameName: String): List<FixOption> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FixOption>()
        val aid = appId.toString()

        // Ryuu (bundled index; multiple badged entries per game). All .zip.
        runCatching {
            loadRyuuIndex()[aid]?.forEach { (file, badge) ->
                out += FixOption(
                    title = cleanName(file),
                    source = "Ryuu",
                    badge = badge.ifBlank { "fix" },
                    url = RYUU_BASE + urlEncode(file),
                    kind = Kind.RYUU,
                )
            }
        }

        // LuaTools index (generic + online)
        runCatching {
            loadLuaIndex()
            if (luaGeneric?.contains(aid) == true) {
                out += FixOption("Generic bypass", "LuaTools", "generic", GENERIC_FIX_URL.format(appId), Kind.GENERIC)
            }
            if (luaOnline?.contains(aid) == true) {
                out += FixOption("Online fix", "LuaTools", "online", ONLINE_FIX_URL.format(appId), Kind.ONLINE)
            }
        }

        // Perondepot (name‑matched online fix; RAR via libarchive)
        runCatching { perondepotMatch(gameName)?.let { out += it } }

        // Unsteam AIO — always available
        out += FixOption("Unsteam (all‑in‑one)", "Unsteam", "emulator", UNSTEAM_AIO_URL, Kind.UNSTEAM)
        out
    }

    /** Downloads and installs [option] into [appId]'s install dir. */
    suspend fun applyFix(appId: Int, option: FixOption): Result<FixResult> = withContext(Dispatchers.IO) {
        runCatching {
            val installDir = File(SteamService.getAppDirPath(appId))
            require(installDir.isDirectory) { "Game is not installed" }
            val isRar = option.url.substringBefore('?').endsWith(".rar", ignoreCase = true)
            val tmp = File.createTempFile("fix_$appId", if (isRar) ".rar" else ".zip")
            try {
                http.newCall(Request.Builder().url(option.url).header("User-Agent", UA).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("Download failed (${resp.code})")
                        resp.body?.byteStream()?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                            ?: throw IllegalStateException("Empty response")
                    }
                val written = if (isRar) extractRar(appId, tmp, installDir)
                    else extractZip(tmp, installDir, appId, option.kind)
                // Force Wine to load the exact DLLs this fix shipped as native, so its
                // crack/emulator/proxy DLLs win over Wine's builtins. Applies to every
                // source (Ryuu, LuaTools, Perondepot, Unsteam) — we override precisely
                // the DLL names the archive dropped, not a fixed guess-list.
                val shippedDlls = written
                    .map { it.substringAfterLast('/').substringAfterLast('\\') }
                    .filter { it.endsWith(".dll", ignoreCase = true) }
                    .map { it.substringBeforeLast('.').lowercase() }
                // A fix may also ship a `dlllist.txt` naming DLLs its loader needs forced
                // native — sometimes ones already present in the game folder (not dropped
                // by this fix). Union those in so they're overridden too.
                val listedDlls = readDllList(installDir, written)
                val overrideDlls = (shippedDlls + listedDlls).distinct()
                val overrides = applyDllOverrides(appId, installDir, overrideDlls)
                Timber.i("Fixes: applied ${option.title} (${option.kind}) for $appId — ${written.size} file(s)")
                FixResult(written.size, overrides)
            } finally {
                tmp.delete()
            }
        }.onFailure { Timber.e(it, "Fixes: applyFix($appId, ${option.title}) failed") }
    }

    // ── sources ──────────────────────────────────────────────────────────────

    private fun loadRyuuIndex(): Map<String, List<Pair<String, String>>> {
        ryuuIndex?.let { return it }
        val ctx = SteamService.instance?.applicationContext ?: return emptyMap()
        val parsed = runCatching {
            val text = ctx.assets.open("luatools/ryuu_index.json").bufferedReader().use { it.readText() }
            val fixes = JSONObject(text).optJSONObject("fixes") ?: return@runCatching emptyMap()
            buildMap<String, List<Pair<String, String>>> {
                fixes.keys().forEach { appid ->
                    val arr = fixes.optJSONArray(appid) ?: return@forEach
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val file = o.optString("file").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        file to o.optString("badge")
                    }
                    if (list.isNotEmpty()) put(appid, list)
                }
            }
        }.getOrDefault(emptyMap())
        ryuuIndex = parsed
        return parsed
    }

    private fun loadLuaIndex() {
        if (luaGeneric != null) return
        runCatching {
            http.newCall(Request.Builder().url(FIXES_INDEX_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val json = JSONObject(resp.body?.string() ?: return@use)
                luaGeneric = json.optJSONArray("genericFixes")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.toHashSet()
                } ?: emptySet()
                luaOnline = json.optJSONArray("onlineFixes")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.toHashSet()
                } ?: emptySet()
            }
        }.onFailure { Timber.w(it, "Fixes: LuaTools index load failed") }
    }

    /** Fetch the Perondepot directory listing and exact‑match [gameName] to a .rar fix. */
    private fun perondepotMatch(gameName: String): FixOption? {
        if (gameName.isBlank()) return null
        val target = normalize(gameName)
        return runCatching {
            http.newCall(Request.Builder().url(PERONDEPOT_BASE).header("User-Agent", UA).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string() ?: return@use null
                    val re = Regex("""href="([^"]+?\.rar)"""", RegexOption.IGNORE_CASE)
                    for (m in re.findAll(html)) {
                        val href = m.groupValues[1]
                        val name = cleanName(urlDecode(href))
                        if (normalize(name) == target) {
                            return@use FixOption(
                                title = "Online fix", source = "Perondepot", badge = "online",
                                url = PERONDEPOT_BASE + href, kind = Kind.PERONDEPOT,
                            )
                        }
                    }
                    null
                }
        }.getOrNull()
    }

    // ── extraction ───────────────────────────────────────────────────────────

    /**
     * Extract a RAR fix into [installDir] using the shared libarchive extractor
     * (RAR4 + RAR5). libarchive wipes its own destination, so we stage into a temp
     * dir and copy files over the install (with a path‑traversal guard). Multi‑volume
     * and encrypted archives surface a clear error from the extractor.
     */
    private suspend fun extractRar(appId: Int, rar: File, installDir: File): List<String> {
        val staging = File(rar.parentFile, "fixrar_${appId}_${System.nanoTime()}")
        try {
            ModArchiveExtractor.extract(rar, staging)
            val base = installDir.canonicalFile
            val stageBase = staging.canonicalFile
            val written = mutableListOf<String>()
            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.canonicalFile.relativeToOrNull(stageBase)?.invariantSeparatorsPath ?: return@forEach
                val target = File(base, rel).canonicalFile
                if (target.path.startsWith(base.path + File.separator)) {
                    target.parentFile?.mkdirs()
                    f.copyTo(target, overwrite = true)
                    written.add(rel)
                }
            }
            writeFixLog(installDir, appId, written)
            return written
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractZip(zip: File, installDir: File, appId: Int, kind: Kind): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { names.add(e.name); z.closeEntry(); e = z.nextEntry }
        }
        val top = names.mapNotNull { it.split('/').firstOrNull()?.takeIf(String::isNotEmpty) }.toSet()
        val strip = if (top.size == 1 && top.first() == appId.toString()) "$appId/" else null
        val base = installDir.canonicalFile
        val written = mutableListOf<String>()
        ZipInputStream(zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val rel = when {
                        strip != null && e.name.startsWith(strip) -> e.name.substring(strip.length)
                        strip != null -> ""
                        else -> e.name
                    }
                    if (rel.isNotEmpty()) {
                        val target = File(base, rel).canonicalFile
                        if (target.path.startsWith(base.path + File.separator)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { o -> z.copyTo(o) }
                            written.add(rel.replace('\\', '/'))
                        }
                    }
                }
                z.closeEntry(); e = z.nextEntry
            }
        }
        if (kind == Kind.ONLINE || kind == Kind.UNSTEAM) patchUnsteamIni(base, written, appId)
        writeFixLog(installDir, appId, written)
        return written
    }

    /**
     * DLL basenames named in a fix's `dlllist.txt`, if it shipped one. Tolerates
     * CRLF, `#` comments, surrounding whitespace and stray path prefixes; keeps
     * only `*.dll` entries (returned lowercased, without extension).
     */
    private fun readDllList(installDir: File, written: List<String>): List<String> {
        val rel = written.firstOrNull { it.substringAfterLast('/').equals("dlllist.txt", ignoreCase = true) }
            ?: return emptyList()
        return runCatching {
            File(installDir, rel).readLines()
                .map { it.substringBefore('#').trim().substringAfterLast('/').substringAfterLast('\\') }
                .filter { it.endsWith(".dll", ignoreCase = true) }
                .map { it.substringBeforeLast('.').lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun patchUnsteamIni(base: File, written: List<String>, appId: Int) {
        written.firstOrNull { it.endsWith("unsteam.ini", true) }?.let { rel ->
            runCatching {
                val ini = File(base, rel)
                val patched = ini.readText().replace("<appid>", appId.toString())
                ini.writeText(patched)
            }
        }
    }

    private fun writeFixLog(installDir: File, appId: Int, written: List<String>) {
        runCatching { File(installDir, "luatools-fix-log-$appId.log").writeText(written.joinToString("\n")) }
    }

    // ── Wine DLL overrides for the fix's own DLLs ────────────────────────────

    /**
     * Manifest of DLL basenames a fix dropped into the game folder. Mirrors the
     * desktop LuaTools plugin's `.slssteam_fix_dlls`: it's the one moment we can
     * tell a fix's DLLs (arbitrary names — `OnlineFix64`, `steam_api64`, `voices38`…)
     * apart from the game's own, so we can force Wine to load exactly those as
     * native. Read again at every launch by [reapplyDllOverrides].
     */
    private const val FIX_DLL_MANIFEST = ".unchained_fix_dlls"

    /**
     * Record [shippedDlls] to the manifest and override each to native in the
     * game's container so Wine loads the fix's DLL instead of its own builtin.
     * Returns the DLL names now overridden (empty if none / on failure).
     */
    private fun applyDllOverrides(appId: Int, installDir: File, shippedDlls: List<String>): List<String> {
        if (shippedDlls.isEmpty()) return emptyList()
        // Persist the cumulative manifest (survives so we can re-apply each launch).
        val all = runCatching {
            val f = File(installDir, FIX_DLL_MANIFEST)
            val prev = if (f.isFile) f.readLines().map { it.trim().lowercase() }.filter { it.isNotBlank() } else emptyList()
            val union = (prev + shippedDlls).distinct().sorted()
            f.writeText(union.joinToString("\n"))
            union
        }.getOrDefault(shippedDlls)

        val ctx = SteamService.instance?.applicationContext ?: return shippedDlls
        runCatching {
            val container = ContainerUtils.getContainer(ctx, appId.toString())
            if (mergeIntoContainer(container, all)) {
                Timber.i("Fixes: DLL overrides for $appId -> ${shippedDlls.joinToString()}")
            }
        }.onFailure { Timber.w(it, "Fixes: DLL override write for $appId failed (recorded to manifest)") }
        return shippedDlls
    }

    /**
     * Launch‑time hook: re‑apply the recorded fix‑DLL native overrides for [appId]
     * onto [container]. Called from GameFixesRegistry before every launch so the
     * overrides survive a container reset/recreation. No‑op when no manifest exists.
     */
    fun reapplyDllOverrides(appId: Int, container: com.winlator.container.Container) {
        runCatching {
            val manifest = File(SteamService.getAppDirPath(appId), FIX_DLL_MANIFEST)
            if (!manifest.isFile) return
            val dlls = manifest.readLines().map { it.trim().lowercase() }.filter { it.isNotBlank() }
            if (dlls.isNotEmpty() && mergeIntoContainer(container, dlls)) {
                Timber.i("Fixes: re‑applied ${dlls.size} DLL override(s) for $appId at launch")
            }
        }.onFailure { Timber.w(it, "Fixes: reapplyDllOverrides($appId) failed") }
    }

    /** Merge [dlls] as native,builtin into [container]'s WINEDLLOVERRIDES; save if changed. */
    private fun mergeIntoContainer(container: com.winlator.container.Container, dlls: List<String>): Boolean {
        val env = EnvVars(container.envVars)
        val current = env.get("WINEDLLOVERRIDES")
        val merged = mergeOverrides(current, dlls)
        if (merged == current) return false
        env.put("WINEDLLOVERRIDES", merged)
        container.envVars = env.toString()
        container.saveData()
        return true
    }

    /** Merge [dlls] (as native,builtin) into an existing WINEDLLOVERRIDES string. */
    private fun mergeOverrides(current: String, dlls: List<String>): String {
        val entries = LinkedHashMap<String, String>()
        current.split(';', ' ').filter { it.isNotBlank() }.forEach { part ->
            val order = if (part.contains('=')) part.substringAfter('=') else "native,builtin"
            part.substringBefore('=').split(',').forEach { n ->
                if (n.isNotBlank()) entries[n.lowercase()] = order
            }
        }
        dlls.forEach { entries[it] = "native,builtin" }
        return entries.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun urlDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Title before a " - <cracker>" suffix, minus the archive extension. */
    private fun cleanName(file: String): String =
        file.substringBeforeLast('.').substringBefore(" - ").trim()

    /** Lowercase, keep only [a-z0-9] — for name matching. */
    private fun normalize(name: String): String =
        name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private const val UA = "Mozilla/5.0"
}
