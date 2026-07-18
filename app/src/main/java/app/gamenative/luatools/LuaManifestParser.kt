package app.gamenative.luatools

/**
 * Result of parsing a LuaTools manifest `.lua` script.
 *
 * @param appId         the app the manifest is for
 * @param depotKeys     depotId -> AES depot decryption key (hex) from `addappid(depot, 1, "key")`
 * @param manifestGids  depotId -> manifest GID from `setManifestid(depot, "gid", size)`
 * @param manifestSizes depotId -> manifest size (bytes)
 * @param appToken      ProductInfo access token from `addtoken(app, "token")`, if any
 * @param dlcDepotIds   depots referenced without a key (DLC / already-owned depots)
 * @param manifestFiles bundled `*.manifest` binaries by filename (may be empty)
 */
data class ParsedManifest(
    val appId: Int,
    val depotKeys: Map<Int, String>,
    val manifestGids: Map<Int, Long>,
    val manifestSizes: Map<Int, Long>,
    val appToken: String?,
    val dlcDepotIds: Set<Int>,
    val manifestFiles: Map<String, ByteArray> = emptyMap(),
) {
    /** All depot IDs this manifest describes (keys + manifest ids + dlc). */
    val depotIds: Set<Int> get() = (depotKeys.keys + manifestGids.keys + dlcDepotIds)
}

/**
 * Parses SteamTools-style `.lua` manifest scripts. Faithful port of the
 * original plugin's parser (process_zip_task._parse_lua / downloads.py):
 *
 * ```
 * addappid(2054970)                       -- main app id (+ optional "-- Name")
 * addappid(2054971, 1, "deadbeef...")     -- depot 2054971 with decryption key
 * addappid(2054972)                       -- DLC / no-key depot
 * setManifestid(2054971, "8471...", 4096) -- manifest GID + size for a depot
 * addtoken(2054970, "AAABBBCCC")          -- ProductInfo access token
 * ```
 */
object LuaManifestParser {
    private val ADDAPPID = Regex("""addappid\(([^)]*)\)""", RegexOption.IGNORE_CASE)
    private val SETMANIFEST =
        Regex("""setManifestid\(\s*(\d+)\s*,\s*"?(\d+)"?\s*(?:,\s*(\d+))?\s*\)""", RegexOption.IGNORE_CASE)
    private val ADDTOKEN = Regex("""addtoken\s*\(\s*(\d+)\s*,\s*"([^"]+)"\s*\)""", RegexOption.IGNORE_CASE)

    fun parse(appId: Int, lua: String, manifestFiles: Map<String, ByteArray> = emptyMap()): ParsedManifest {
        val depotKeys = LinkedHashMap<Int, String>()
        val dlcDepots = LinkedHashSet<Int>()

        var mainAppId = if (appId > 0) appId else 0
        var first = true
        for (match in ADDAPPID.findAll(lua)) {
            val args = match.groupValues[1].split(",").map { it.trim() }
            val id = args.getOrNull(0)?.toIntOrNull() ?: continue
            if (first) {
                if (mainAppId == 0) mainAppId = id
                first = false
            }
            // third argument, if present and non-empty, is the hex depot key
            val key = args.getOrNull(2)?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
            when {
                key != null -> depotKeys[id] = key
                id != mainAppId -> dlcDepots.add(id)
            }
        }

        val gids = LinkedHashMap<Int, Long>()
        val sizes = LinkedHashMap<Int, Long>()
        for (match in SETMANIFEST.findAll(lua)) {
            val depot = match.groupValues[1].toIntOrNull() ?: continue
            val gid = match.groupValues[2].toLongOrNull() ?: continue
            gids[depot] = gid
            match.groupValues.getOrNull(3)?.toLongOrNull()?.let { sizes[depot] = it }
        }

        val token = ADDTOKEN.find(lua)?.groupValues?.get(2)

        return ParsedManifest(
            appId = if (appId > 0) appId else mainAppId,
            depotKeys = depotKeys,
            manifestGids = gids,
            manifestSizes = sizes,
            appToken = token,
            dlcDepotIds = dlcDepots,
            manifestFiles = manifestFiles,
        )
    }
}
