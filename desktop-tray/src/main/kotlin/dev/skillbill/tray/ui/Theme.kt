package dev.skillbill.tray.ui

import dev.skillbill.tray.domain.DesktopUiScaleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val canvas = Color(0xFF0E0E11)
internal val sidebar = Color(0xFF141417)
internal val panel = Color(0xFF17171B)
internal val raised = Color(0xFF1F1F24)
internal val surface3 = Color(0xFF2A2A31)
internal val strongBorder = Color(0xFF383840)
internal val titlebar = Color(0xFF08080A)
internal val border = Color(0xFF26262C)
internal val ink = Color(0xFFEDEDF0)
internal val muted = Color(0xFF9C9CA6)
internal val dim = Color(0xFF6A6A74)
internal val mint = Color(0xFFE9A81C)
internal val amber = Color(0xFFF3C462)
internal val orange = Color(0xFFE2703C)
internal val red = Color(0xFFF0555B)
internal val green = Color(0xFF3FB950)
internal val blue = Color(0xFF6FA8DC)
internal val mono = FontFamily(Font("fonts/JetBrainsMono.ttf"))
internal val body = FontFamily(Font("fonts/Inter.ttf"))
internal val display = FontFamily(Font("fonts/SpaceGrotesk.ttf"))

@Composable
fun SkillBillTheme(uiScale: DesktopUiScaleConfiguration = DesktopUiScaleConfiguration(), content: @Composable () -> Unit) {
  DesktopUiScaleProvider(uiScale) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = mint, onPrimary = Color(0xFF14100A), secondary = amber,
      background = canvas, onBackground = ink, surface = panel, onSurface = ink,
      surfaceVariant = raised, onSurfaceVariant = muted, outline = border, error = red,
    ),
    typography = Typography(
      headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 29.sp),
      titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
      titleMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
      titleSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
      bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
      bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
      bodySmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
      labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
      labelMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
      labelSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 15.sp),
    ),
    content = content,
  )
}
}
