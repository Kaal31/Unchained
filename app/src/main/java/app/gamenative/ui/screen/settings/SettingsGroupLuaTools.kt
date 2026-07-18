package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.luatools.LuaToolsPrefs
import app.gamenative.luatools.LuaToolsRepository
import app.gamenative.luatools.ManifestSource
import app.gamenative.luatools.ManifestSources
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import app.gamenative.luatools.LuaToolsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings group for the ported LuaTools integration: toggle the feature,
 * enable/disable manifest sources, enter optional per-source API keys, and add
 * a non-owned game to the library by AppID.
 */
@Composable
fun SettingsGroupLuaTools() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { LuaToolsClient() }

    LaunchedEffect(Unit) { runCatching { LuaToolsPrefs.init(context) } }

    SettingsGroup {
        var enabled by remember { mutableStateOf(true) }
        val sources = remember { mutableStateListOf<ManifestSource>() }
        val keyFields = remember { mutableStateListOf<String>() }

        LaunchedEffect(Unit) {
            enabled = LuaToolsPrefs.enabled
            sources.clear(); sources.addAll(LuaToolsPrefs.getSources())
            keyFields.clear(); keyFields.addAll(LuaToolsPrefs.requiredKeyFields())
        }

        SettingsSwitch(
            colors = settingsTileColors(),
            state = enabled,
            title = { Text(stringResource(R.string.lt_enable_title)) },
            subtitle = { Text(stringResource(R.string.lt_enable_subtitle)) },
            onCheckedChange = {
                enabled = it
                LuaToolsPrefs.enabled = it
            },
        )

        // ── source toggles ──────────────────────────────────────────────────
        sources.forEachIndexed { index, source ->
            val keyed = ManifestSources.placeholdersIn(source.url).isNotEmpty()
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                state = source.enabled,
                title = { Text(source.name) },
                subtitle = { Text(stringResource(if (keyed) R.string.lt_source_needs_key else R.string.lt_source_free)) },
                onCheckedChange = { on ->
                    LuaToolsPrefs.setSourceEnabled(source.name, on)
                    sources[index] = source.copy(enabled = on)
                },
            )
        }

        // ── optional API keys (Hubcap shows a live quota bar) ───────────────
        keyFields.forEach { placeholder ->
            var draft by remember(placeholder) { mutableStateOf(LuaToolsPrefs.getApiKey(placeholder)) }
            var saveStatus by remember(placeholder) { mutableStateOf("") }
            var stats by remember(placeholder) { mutableStateOf<LuaToolsClient.HubcapStats?>(null) }
            // Hubcap is the only key-gated source; its placeholder carries the quota.
            val isHubcap = ManifestSources.placeholdersIn(
                ManifestSources.DEFAULTS.firstOrNull { it.name == "Hubcap" }?.url.orEmpty(),
            ).contains(placeholder)

            // Live quota: refresh on entry and every 30s while a key is present.
            if (isHubcap) {
                LaunchedEffect(placeholder) {
                    while (true) {
                        val key = LuaToolsPrefs.getApiKey(placeholder)
                        stats = if (key.isNotBlank()) {
                            withContext(Dispatchers.IO) { client.fetchHubcapStats(key) }
                        } else {
                            null
                        }
                        delay(30_000)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.lt_key_label, ManifestSources.labelFor(placeholder))) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        LuaToolsPrefs.setApiKey(placeholder, draft)
                        saveStatus = context.getString(R.string.lt_key_saved)
                        if (isHubcap) {
                            saveStatus = context.getString(R.string.lt_key_checking)
                            scope.launch {
                                val s = withContext(Dispatchers.IO) { client.fetchHubcapStats(draft) }
                                stats = s
                                saveStatus = if (s != null) {
                                    context.getString(R.string.lt_key_valid)
                                } else {
                                    context.getString(R.string.lt_key_invalid)
                                }
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(stringResource(R.string.lt_save_key)) }

                if (saveStatus.isNotBlank()) {
                    Text(
                        text = saveStatus,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Live quota bar
                stats?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.lt_quota_label, s.remaining, s.dailyLimit),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = { s.remaining.toFloat() / s.dailyLimit.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    if (s.resetText.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.lt_quota_reset, s.resetText),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = (if (s.username.isNotBlank()) s.username + " · " else "") +
                            stringResource(R.string.lt_quota_account, s.totalCalls, s.expiresText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── add game by AppID ───────────────────────────────────────────────
        var appIdText by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = appIdText,
                onValueChange = { v -> appIdText = v.filter { it.isDigit() }.take(9) },
                label = { Text(stringResource(R.string.lt_add_by_appid)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && appIdText.isNotBlank(),
                onClick = {
                    val id = appIdText.toIntOrNull() ?: return@Button
                    busy = true
                    status = context.getString(R.string.lt_fetching)
                    scope.launch {
                        val result = LuaToolsRepository.addGame(id)
                        busy = false
                        status = result.fold(
                            onSuccess = {
                                context.getString(
                                    R.string.lt_add_success,
                                    it.appId, it.depotKeyCount, it.manifestIdCount,
                                )
                            },
                            onFailure = { context.getString(R.string.lt_add_failed, it.message ?: "") },
                        )
                    }
                },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text(stringResource(if (busy) R.string.lt_working else R.string.lt_add_game)) }

            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
