package dev.skillbill.tray.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import dev.skillbill.tray.core.designsystem.monoFontBytes
import java.awt.Color
import java.awt.Font
import kotlin.coroutines.cancellation.CancellationException

private const val TERMINAL_FONT_SIZE = 13

data class TerminalColors(
  val background: Color,
  val foreground: Color,
  val selectionBackground: Color,
  val selectionForeground: Color,
)

/** The terminal palette follows whichever [MaterialTheme] is active; the ANSI colors stay at JediTerm's defaults. */
@Composable
fun terminalColors(): TerminalColors {
  val scheme = MaterialTheme.colorScheme
  return TerminalColors(
    background = Color(scheme.background.toArgb(), true),
    foreground = Color(scheme.onBackground.toArgb(), true),
    selectionBackground = Color(scheme.primary.toArgb(), true),
    selectionForeground = Color(scheme.onPrimary.toArgb(), true),
  )
}

class TerminalSettingsProvider(private val colors: TerminalColors, private val font: Font) : DefaultSettingsProvider() {
  override fun getDefaultForeground(): TerminalColor = colors.foreground.terminalColor()

  override fun getDefaultBackground(): TerminalColor = colors.background.terminalColor()

  @Deprecated("Kept because JediTermWidget 3.57 seeds its StyleState from it; derived from the two getters above.")
  override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())

  // JediTerm ignores getSelectionColor while inverse selection is on, which is its default.
  override fun useInverseSelectionColor(): Boolean = false

  override fun getSelectionColor(): TextStyle =
    TextStyle(colors.selectionForeground.terminalColor(), colors.selectionBackground.terminalColor())

  override fun getTerminalFont(): Font = font

  override fun getTerminalFontSize(): Float = TERMINAL_FONT_SIZE.toFloat()
}

private fun Color.terminalColor() = TerminalColor(red, green, blue)

/** JetBrains Mono from the design system, or the platform monospaced font when it cannot be read; never blocks the terminal. */
suspend fun terminalFont(): Font = try {
  Font.createFont(Font.TRUETYPE_FONT, monoFontBytes().inputStream()).deriveFont(Font.PLAIN, TERMINAL_FONT_SIZE.toFloat())
} catch (cancellation: CancellationException) {
  throw cancellation
} catch (_: Exception) {
  Font(Font.MONOSPACED, Font.PLAIN, TERMINAL_FONT_SIZE)
}
