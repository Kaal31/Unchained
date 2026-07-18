package app.gamenative.luatools

import java.util.concurrent.ConcurrentHashMap

/**
 * Central lookup for LuaTools-supplied depot decryption keys.
 *
 * This is the single seam JavaSteam's DepotDownloader should consult before it
 * asks Steam for a depot key: for a non-owned game the account has no licence,
 * so `SteamApps.getDepotDecryptionKey` fails — but LuaTools already parsed the
 * key out of the manifest `.lua`. See INTEGRATION.md for the exact hook.
 */
object LuaDepotKeyProvider {
    private val keys = ConcurrentHashMap<Int, String>()
    @Volatile private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { keys.putAll(LuaToolsPrefs.getDepotKeys()) }
            loaded = true
        }
    }

    fun put(depotId: Int, hexKey: String) {
        keys[depotId] = hexKey
    }

    fun putAll(map: Map<Int, String>) {
        keys.putAll(map)
    }

    /** Hex-encoded key, or null if LuaTools has none for this depot. */
    fun hexKeyFor(depotId: Int): String? {
        ensureLoaded()
        return keys[depotId]
    }

    /** Raw 32-byte AES key, or null. Convenient for the downloader. */
    fun keyBytesFor(depotId: Int): ByteArray? = hexKeyFor(depotId)?.let(::hexToBytes)

    fun hasKey(depotId: Int): Boolean = hexKeyFor(depotId) != null

    /** All known depot keys as raw AES bytes (depotId -> 32-byte key). */
    fun allBytes(): Map<Int, ByteArray> {
        ensureLoaded()
        return keys.entries.mapNotNull { (id, hex) ->
            runCatching { id to hexToBytes(hex) }.getOrNull()
        }.toMap()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }
}
