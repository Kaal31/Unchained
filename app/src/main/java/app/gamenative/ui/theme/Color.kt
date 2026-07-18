package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults

// Custom color scheme — Steam-style gray-blue
val customBackground = Color(0xFF171A21)
val customForeground = Color(0xFFC7D5E0)
val customCard = Color(0xFF1B2838)
val customCardForeground = Color(0xFFC7D5E0)
val customPrimary = Color(0xFF1A9FFF)
val customPrimaryForeground = Color(0xFFFFFFFF)
val customSecondary = Color(0xFF2A3F5A)
val customSecondaryForeground = Color(0xFFC7D5E0)
val customMuted = Color(0xFF2A3F5A)
val customMutedForeground = Color(0xFF8F98A0)
val customAccent = Color(0xFF66C0F4)
val customAccentForeground = Color(0xFF0E141B)
val customDestructive = Color(0xFF7F1D1D)

val pluviaSeedColor = Color(0xFF1A9FFF)

/**
 * Raw color primitives for the Pluvia app.
 * These are the base colors used to construct theme palettes.
 */

// Brand — Steam gray-blue
val PluviaPrimary = Color(0xFF1A9FFF)
val PluviaSeed = Color(0xFF1A9FFF)

// Backgrounds — Steam dark blue-grays. Card/surface match the background so the
// main app background is one uniform dark; panels use SurfaceElevated/Secondary.
val PluviaBackground = Color(0xFF171A21)
val PluviaSurface = Color(0xFF171A21)
val PluviaSurfaceElevated = Color(0xFF2A3F5A)
val PluviaCard = Color(0xFF171A21)

// Foregrounds — light blue-gray text
val PluviaForeground = Color(0xFFC7D5E0)
val PluviaForegroundMuted = Color(0xFF8F98A0)

// Secondary
val PluviaSecondary = Color(0xFF2A3F5A)

// Accents
val PluviaCyan = Color(0xFF66C0F4)
val PluviaPurple = Color(0xFF8B5CF6)
val PluviaPink = Color(0xFFEC4899)

// Semantic
val PluviaSuccess = Color(0xFF10B981)
val PluviaWarning = Color(0xFFF59E0B)
val PluviaDanger = Color(0xFFEF4444)
val PluviaDestructive = Color(0xFF7F1D1D)

// Border
val PluviaBorder = Color(0xFF3D4C5F)

// Status - Installed/Download states
val StatusInstalled = Color(0xFF4CAF50)
val StatusDownloading = Color(0xFF00BCD4)
val StatusAvailable = Color(0xFF2196F3)
val StatusAway = Color(0xFFFF9800)
val StatusOffline = Color(0xFF9E9E9E)

// Friend states
val FriendOnline = Color(0xFF6DCFF6)
val FriendOffline = Color(0xFF7A7A7A)
val FriendInGame = Color(0xFF90BA3C)
val FriendAwayOrSnooze = Color(0x806DCFF6)
val FriendInGameAwayOrSnooze = Color(0x8090BA3C)
val FriendBlocked = Color(0xFF983D3D)

// Compatibility
val CompatibilityGood = Color(0xFF4CAF50)
val CompatibilityGoodBg = Color(0xFF1B5E20)
val CompatibilityPartial = Color(0xFF8BC34A)
val CompatibilityPartialBg = Color(0xFF33691E)
val CompatibilityUnknown = Color(0xFF9E9E9E)
val CompatibilityUnknownBg = Color(0xFF424242)
val CompatibilityBad = Color(0xFFEF5350)
val CompatibilityBadBg = Color(0xFFB71C1C)
