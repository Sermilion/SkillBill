package dev.skillbill.tray.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.generated.resources.Res
import dev.skillbill.tray.core.designsystem.generated.resources.inter
import dev.skillbill.tray.core.designsystem.generated.resources.jetbrains_mono
import dev.skillbill.tray.core.designsystem.generated.resources.space_grotesk
import org.jetbrains.compose.resources.Font

val mono: FontFamily
  @Composable get() = Font(Res.font.jetbrains_mono).let { font -> remember(font) { FontFamily(font) } }
val body: FontFamily
  @Composable get() = Font(Res.font.inter).let { font -> remember(font) { FontFamily(font) } }
val display: FontFamily
  @Composable get() = Font(Res.font.space_grotesk).let { font -> remember(font) { FontFamily(font) } }

@Composable
fun DesignSystemTheme(content: @Composable () -> Unit) {
  val displayFont = display
  val bodyFont = body
  val monoFont = mono
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = mint, onPrimary = Color(0xFF14100A), secondary = amber,
      background = canvas, onBackground = ink, surface = panel, onSurface = ink,
      surfaceVariant = raised, onSurfaceVariant = muted, outline = border, error = red,
    ),
    typography = Typography(
      headlineSmall = TextStyle(fontFamily = displayFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 29.sp),
      titleLarge = TextStyle(fontFamily = displayFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
      titleMedium = TextStyle(fontFamily = displayFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
      titleSmall = TextStyle(fontFamily = bodyFont, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
      bodyLarge = TextStyle(fontFamily = bodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
      bodyMedium = TextStyle(fontFamily = bodyFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
      bodySmall = TextStyle(fontFamily = bodyFont, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 16.sp),
      labelLarge = TextStyle(fontFamily = bodyFont, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
      labelMedium = TextStyle(fontFamily = monoFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
      labelSmall = TextStyle(fontFamily = monoFont, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 15.sp),
    ),
    content = content,
  )
}
