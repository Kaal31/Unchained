package app.gamenative.luatools

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Tracks which Steam apps ship with Denuvo Anti-Tamper so the UI can badge them.
 *
 * Detection is accuracy-first (no false positives):
 *  1. Steam's own declared DRM notices (`drm_notice` / `legal_notice`) are parsed
 *     for "Denuvo" — this is authoritative and self-populating as games are seen.
 *  2. An optional curated remote list ([REMOTE_LIST_URL], a JSON array of appIds)
 *     supplements titles Steam doesn't declare. Off unless a URL is configured.
 *
 * Results are cached to disk so a game is only checked once. The flag store is a
 * Compose snapshot map, so badges appear automatically once a check completes.
 */
object DenuvoRepository {

    /** appId -> true when Denuvo is present. Compose-observable so cards recompose. */
    private val flagged = mutableStateMapOf<Int, Boolean>()

    /** appIds already resolved (present or absent) so we don't re-query. */
    private val checked = ConcurrentHashMap.newKeySet<Int>()

    private val client = LuaToolsClient()
    private val http = OkHttpClient()
    private val gate = Semaphore(3) // keep Steam appdetails requests gentle
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var initialized = false
    private var cacheFile: File? = null

    fun hasDenuvo(appId: Int): Boolean = flagged[appId] == true

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        cacheFile = File(context.filesDir, "denuvo_cache.json")
        runCatching { loadCache() }.onFailure { Timber.w(it, "Denuvo: cache load failed") }
        if (REMOTE_LIST_URL.isNotBlank()) {
            scope.launch { runCatching { loadRemote() }.onFailure { Timber.w(it, "Denuvo: remote load failed") } }
        }
    }

    /**
     * Lazily resolves [appId] via Steam's DRM notice (once, cached). Safe to call
     * from a card's LaunchedEffect for every visible game.
     */
    fun check(appId: Int) {
        if (appId <= 0 || appId in checked) return
        scope.launch {
            gate.withPermit {
                if (appId in checked) return@withPermit
                val drm = runCatching { client.fetchDrmText(appId) }.getOrNull()
                if (drm == null) return@withPermit // transient failure — allow a later retry
                checked.add(appId)
                if (drm.contains("denuvo", ignoreCase = true)) flagged[appId] = true
                saveCache()
            }
        }
    }

    /** Records a Denuvo hit from text already fetched elsewhere (e.g. LuaTools add). */
    fun recordFromText(appId: Int, text: String?) {
        if (appId <= 0 || text.isNullOrBlank()) return
        checked.add(appId)
        if (text.contains("denuvo", ignoreCase = true)) {
            flagged[appId] = true
            saveCache()
        }
    }

    private fun loadCache() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        val json = JSONObject(file.readText())
        json.optJSONArray("denuvo")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optInt(i); if (id > 0) { flagged[id] = true; checked.add(id) }
            }
        }
        json.optJSONArray("checked")?.let { arr ->
            for (i in 0 until arr.length()) arr.optInt(i).takeIf { it > 0 }?.let(checked::add)
        }
    }

    @Synchronized
    private fun saveCache() {
        val file = cacheFile ?: return
        runCatching {
            val obj = JSONObject()
                .put("denuvo", JSONArray(flagged.keys.toList()))
                .put("checked", JSONArray(checked.toList()))
            file.writeText(obj.toString())
        }
    }

    /** Optional curated supplement: a JSON array of Denuvo appIds. */
    private fun loadRemote() {
        http.newCall(Request.Builder().url(REMOTE_LIST_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body?.string() ?: return
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                arr.optInt(i).takeIf { it > 0 }?.let { flagged[it] = true; checked.add(it) }
            }
            saveCache()
        }
    }

    /**
     * Optional curated Denuvo appId list (JSON array of ints). Leave blank to rely
     * solely on Steam's declared DRM notices. Point at a maintained list to widen
     * coverage to titles Steam doesn't self-declare.
     */
    private const val REMOTE_LIST_URL = ""
}
