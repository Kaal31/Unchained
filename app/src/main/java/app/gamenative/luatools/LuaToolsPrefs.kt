package app.gamenative.luatools

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * DataStore-backed preferences for the LuaTools integration, self-contained so
 * it doesn't have to touch [app.gamenative.PrefManager]. Stores the manifest
 * source list, per-source API keys, the depot-key cache and the feature toggle.
 */
object LuaToolsPrefs {
    private val Context.luaToolsDataStore by preferencesDataStore(name = "LuaToolsPreferences")
    private lateinit var ds: DataStore<Preferences>
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun init(context: Context) {
        ds = context.applicationContext.luaToolsDataStore
    }

    private val ENABLED = booleanPreferencesKey("enabled")
    private val SOURCES = stringPreferencesKey("sources_json")
    private val API_KEYS = stringPreferencesKey("api_keys_json")
    private val DEPOT_KEYS = stringPreferencesKey("depot_keys_json")
    private val APP_TOKENS = stringPreferencesKey("app_tokens_json")
    private val ADDED_APPS = stringPreferencesKey("added_apps_json")

    private fun <T> get(key: Preferences.Key<T>, def: T): T = runBlocking { ds.data.first()[key] ?: def }
    private fun <T> set(key: Preferences.Key<T>, value: T) { runBlocking { ds.edit { it[key] = value } } }

    var enabled: Boolean
        get() = get(ENABLED, true)
        set(v) { set(ENABLED, v) }

    // ── manifest sources ────────────────────────────────────────────────────
    fun getSources(): List<ManifestSource> {
        val raw = get(SOURCES, "")
        if (raw.isBlank()) return ManifestSources.DEFAULTS
        return runCatching { json.decodeFromString<ManifestSourceList>(raw).apiList }
            .getOrDefault(ManifestSources.DEFAULTS)
            .ifEmpty { ManifestSources.DEFAULTS }
    }

    fun setSources(list: List<ManifestSource>) {
        set(SOURCES, json.encodeToString(ManifestSourceList(list)))
    }

    fun setSourceEnabled(name: String, enabled: Boolean) {
        setSources(getSources().map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    // ── per-source API keys (placeholder -> key) ────────────────────────────
    fun getApiKeys(): Map<String, String> {
        val raw = get(API_KEYS, "")
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    fun getApiKey(placeholder: String): String = getApiKeys()[placeholder].orEmpty()

    fun setApiKey(placeholder: String, key: String) {
        val m = getApiKeys().toMutableMap()
        m[placeholder] = key.trim()
        set(API_KEYS, json.encodeToString(m))
    }

    /** Distinct key placeholders required by the currently-enabled sources. */
    fun requiredKeyFields(): List<String> =
        getSources().filter { it.enabled }
            .flatMap { ManifestSources.placeholdersIn(it.url) }
            .distinct()

    // ── depot decryption keys (depotId -> hex) ──────────────────────────────
    fun getDepotKeys(): Map<Int, String> {
        val raw = get(DEPOT_KEYS, "")
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw).mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
    }

    fun putDepotKeys(keys: Map<Int, String>) {
        if (keys.isEmpty()) return
        val merged = getDepotKeys().toMutableMap()
        merged.putAll(keys)
        set(DEPOT_KEYS, json.encodeToString(merged.mapKeys { it.key.toString() }))
    }

    // ── app tokens (appId -> ProductInfo access token) ──────────────────────
    fun getAppTokens(): Map<Int, String> {
        val raw = get(APP_TOKENS, "")
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw).mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
    }

    fun putAppToken(appId: Int, token: String) {
        if (token.isBlank()) return
        val merged = getAppTokens().toMutableMap()
        merged[appId] = token
        set(APP_TOKENS, json.encodeToString(merged.mapKeys { it.key.toString() }))
    }

    // ── LuaTools-added apps (survive the Steam licence sync) ─────────────────
    /**
     * App IDs added via LuaTools. These have no real Steam ownership, so the
     * per-launch licence sync would otherwise prune their synthetic licence and
     * hide them from the library. Persisted so the sync can skip over them.
     */
    fun getAddedAppIds(): Set<Int> {
        val raw = get(ADDED_APPS, "")
        if (raw.isBlank()) return emptySet()
        return runCatching { json.decodeFromString<List<Int>>(raw).toSet() }.getOrDefault(emptySet())
    }

    fun addAddedAppId(appId: Int) {
        val merged = getAddedAppIds().toMutableSet()
        if (merged.add(appId)) set(ADDED_APPS, json.encodeToString(merged.toList()))
    }

    fun removeAddedAppId(appId: Int) {
        val merged = getAddedAppIds().toMutableSet()
        if (merged.remove(appId)) set(ADDED_APPS, json.encodeToString(merged.toList()))
    }
}
