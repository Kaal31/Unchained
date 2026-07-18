package app.gamenative.luatools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A LuaTools manifest source. Mirrors the entries in the original plugin's
 * `api.json` / `load_free_manifest_apis` list: a named endpoint whose URL may
 * contain the `<appid>` placeholder and, optionally, one or more API-key
 * placeholders (e.g. `<moapikey>`).
 */
@Serializable
data class ManifestSource(
    val name: String,
    val url: String,
    @SerialName("success_code") val successCode: Int = 200,
    @SerialName("unavailable_code") val unavailableCode: Int = 404,
    val enabled: Boolean = true,
)

@Serializable
data class ManifestSourceList(
    @SerialName("api_list") val apiList: List<ManifestSource> = emptyList(),
)

object ManifestSources {
    const val APPID_PLACEHOLDER = "<appid>"

    /** Any placeholder whose name contains "key" is treated as a user-supplied API key. */
    val KEY_PLACEHOLDER = Regex("<[a-zA-Z0-9_]*key[a-zA-Z0-9_]*>", RegexOption.IGNORE_CASE)

    /** Bundled default free sources (Hubcap is key-gated; Ryuu & Sushi are keyless). */
    val DEFAULTS: List<ManifestSource> = listOf(
        ManifestSource("Hubcap", "https://hubcapmanifest.com/api/v1/manifest/<appid>?api_key=<moapikey>"),
        ManifestSource("Ryuu", "http://167.235.229.108/<appid>"),
        ManifestSource("TwentyTwo Cloud", "https://api.twentytwocloud.com/download?appid=<appid>"),
        ManifestSource("Sushi", "https://raw.githubusercontent.com/sushi-dev55-alt/sushitools-games-repo-alt/refs/heads/main/<appid>.zip"),
    )

    private val PLACEHOLDER_LABELS = mapOf("<moapikey>" to "Hubcap API key")

    fun placeholdersIn(url: String): List<String> =
        KEY_PLACEHOLDER.findAll(url).map { it.value }.distinct().toList()

    fun labelFor(placeholder: String): String =
        PLACEHOLDER_LABELS[placeholder]
            ?: placeholder.trim('<', '>').replace('_', ' ')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
