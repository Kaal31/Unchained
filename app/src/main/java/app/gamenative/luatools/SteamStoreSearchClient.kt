package app.gamenative.luatools

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Thin client over Steam's public store-search endpoint. Lets the library search
 * find *any* Steam title by name — including games the account doesn't own — so
 * they can be added via LuaTools/SLS. No authentication required.
 */
class SteamStoreSearchClient(
    private val http: OkHttpClient = OkHttpClient(),
) {
    /** A single store-search hit. */
    data class Result(
        val appId: Int,
        val name: String,
    )

    /** One page of results plus Steam's reported total for the query. */
    data class Page(
        val results: List<Result>,
        val total: Int,
    )

    /** Convenience: first [limit] results only (no total). */
    fun search(term: String, limit: Int = 100): List<Result> = searchPage(term, 0, limit).results

    /**
     * Fetches results [start]..[start]+[count] for [term] via the full store-search
     * endpoint (the one the Steam site uses; the `storesearch` API is capped at 10).
     * Returns the page plus Steam's `total_count` so callers can page on demand.
     */
    fun searchPage(term: String, start: Int, count: Int = 100): Page {
        val q = term.trim()
        if (q.length < 2) return Page(emptyList(), 0)
        return runCatching {
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            // infinite=1 makes Steam return JSON { results_html, total_count } instead of a full page.
            val url = "https://store.steampowered.com/search/results/?term=$enc" +
                "&start=$start&count=$count&infinite=1&cc=US&l=english"
            http.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use Page(emptyList(), 0)
                val json = JSONObject(resp.body?.string() ?: return@use Page(emptyList(), 0))
                val total = json.optInt("total_count", 0)
                val html = json.optString("results_html")
                if (html.isBlank()) return@use Page(emptyList(), total)
                // Each result row: <a ... data-ds-appid="ID" ...> ... <span class="title">NAME</span>
                val rowRegex = Regex("""data-ds-appid="(\d+)"[\s\S]*?class="title">([^<]+)<""")
                val seen = HashSet<Int>()
                val results = buildList {
                    for (m in rowRegex.findAll(html)) {
                        val id = m.groupValues[1].toIntOrNull() ?: continue
                        if (!seen.add(id)) continue
                        val name = decodeEntities(m.groupValues[2].trim())
                        if (name.isNotEmpty()) add(Result(appId = id, name = name))
                    }
                }
                Page(results, total)
            }
        }.getOrElse {
            Timber.w(it, "SteamStoreSearch: '$q' failed")
            Page(emptyList(), 0)
        }
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&trade;", "™")
        .replace("&reg;", "®")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"
    }
}
