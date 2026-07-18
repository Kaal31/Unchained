package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R

/**
 * Red badge marking a game as protected by Denuvo Anti-Tamper. Shown alongside the
 * [CompatibilityBadge]. Pill (icon + "Denuvo") for list views, icon-only for grids.
 */
@Composable
fun DenuvoBadge(
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val background = Color(0xE6B71C1C) // deep red
    val tint = Color(0xFFFF6B6B) // light red

    if (showLabel) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.denuvo_label),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Medium,
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(24.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = stringResource(R.string.denuvo_anti_tamper),
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
