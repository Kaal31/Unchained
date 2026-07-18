package app.gamenative.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.luatools.FixesRepository
import app.gamenative.luatools.LuaToolsPrefs
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.launch

/**
 * "Install Fix" control for LuaTools/SLS games. Tapping it opens a list of every
 * available fix for the game (Ryuu, LuaTools generic/online, Perondepot, Unsteam),
 * each shown with its source and a colour-coded badge, and installs the chosen one.
 */
@Composable
fun FixInstallSection(appId: Int, gameName: String, modifier: Modifier = Modifier) {
    val isSls = remember(appId) {
        runCatching { LuaToolsPrefs.getAddedAppIds().contains(appId) }.getOrDefault(false)
    }
    if (!isSls || appId <= 0) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var options by remember(appId) { mutableStateOf<List<FixesRepository.FixOption>?>(null) }
    var loadingList by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            if (installing) return@Button
            showDialog = true
            if (options == null && !loadingList) {
                loadingList = true
                scope.launch {
                    options = runCatching { FixesRepository.listFixes(appId, gameName) }
                        .getOrDefault(emptyList())
                    loadingList = false
                }
            }
        },
        enabled = !installing,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = stringResource(if (installing) R.string.fix_installing else R.string.fix_install_button),
                modifier = Modifier.padding(start = 8.dp),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!installing) showDialog = false },
            icon = { Icon(Icons.Default.Build, contentDescription = null) },
            title = { Text(stringResource(R.string.fix_choose_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val opts = options
                    when {
                        loadingList -> Loading(stringResource(R.string.fix_loading))
                        installing -> Loading(stringResource(R.string.fix_installing))
                        opts.isNullOrEmpty() -> Text(
                            stringResource(R.string.fix_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> opts.forEach { opt ->
                            FixOptionRow(
                                option = opt,
                                enabled = !installing,
                                onClick = {
                                    if (installing) return@FixOptionRow
                                    installing = true
                                    scope.launch {
                                        val res = FixesRepository.applyFix(appId, opt)
                                        installing = false
                                        showDialog = false
                                        res.onSuccess { r ->
                                            val msg = if (r.dllOverrides.isNotEmpty()) {
                                                context.getString(
                                                    R.string.fix_success_overrides,
                                                    r.files,
                                                    r.dllOverrides.joinToString(", "),
                                                )
                                            } else {
                                                context.getString(R.string.fix_success, r.files)
                                            }
                                            SnackbarManager.show(msg)
                                        }.onFailure {
                                            SnackbarManager.show(
                                                context.getString(R.string.fix_failed, it.message ?: ""),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** A single tappable fix, showing its title, source chip and a colour-coded badge. */
@Composable
private fun FixOptionRow(
    option: FixesRepository.FixOption,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        // Darker than the dialog container (surfaceContainerHigh) so rows read as cards.
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        text = option.source,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (option.badge.isNotBlank()) {
                        val (bg, fg) = badgeColors(option.badge)
                        Pill(text = option.badge.replaceFirstChar { it.uppercase() }, container = bg, content = fg)
                    }
                }
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Small rounded label. */
@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Badge → (container, content) colours. Muted hues that read well on the dark theme. */
private fun badgeColors(badge: String): Pair<Color, Color> = when (badge.lowercase()) {
    "online" -> Color(0xFF1E6FB8) to Color.White
    "bypass", "crack" -> Color(0xFFC98A00) to Color(0xFF1A1300)
    "tested" -> Color(0xFF2E7D32) to Color.White
    "unstable" -> Color(0xFFB03A3A) to Color.White
    "extra_steps" -> Color(0xFFC96A1B) to Color.White
    "generic" -> Color(0xFF546E7A) to Color.White
    "emulator" -> Color(0xFF6A4BA0) to Color.White
    else -> Color(0xFF455A64) to Color.White
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
