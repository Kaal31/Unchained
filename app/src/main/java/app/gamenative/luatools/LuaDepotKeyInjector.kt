package app.gamenative.luatools

import java.lang.reflect.Field
import timber.log.Timber

/**
 * Seeds LuaTools-supplied depot decryption keys into JavaSteam's DepotDownloader
 * at runtime, so a non-owned depot — for which Steam refuses to hand out a key —
 * can still be decrypted.
 *
 * JavaSteam's `Steam3Session.requestDepotKey()` short-circuits whenever the key
 * is already present in its `depotKeys` map. By reflecting into the downloader's
 * `steam3` session and pre-populating that map before the download starts, we
 * make it use our keys instead of asking Steam. This needs no change to (or
 * rebuild of) the JavaSteam dependency.
 *
 * Note: relies on the internal field names `steam3` / `depotKeys`, which are
 * preserved in debug builds. If a future JavaSteam version renames them the seed
 * simply no-ops (logged) and owned games are unaffected.
 */
object LuaDepotKeyInjector {

    fun seed(depotDownloader: Any?) {
        if (depotDownloader == null) return
        val keys = LuaDepotKeyProvider.allBytes()
        if (keys.isEmpty()) return

        runCatching {
            val steam3 = readField(depotDownloader, "steam3") ?: return
            @Suppress("UNCHECKED_CAST")
            val depotKeys = readField(steam3, "depotKeys") as? MutableMap<Int, ByteArray> ?: return

            var added = 0
            keys.forEach { (depotId, key) ->
                if (depotKeys.putIfAbsent(depotId, key) == null) added++
            }
            Timber.i("LuaTools: seeded $added depot key(s) into DepotDownloader")
        }.onFailure {
            Timber.w(it, "LuaTools: depot key injection failed (JavaSteam internals changed?)")
        }
    }

    /** Reads a (possibly private/internal) field by name, walking up the class hierarchy. */
    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}
