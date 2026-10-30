package dev.skillbill.desktop.feature.terminal

import androidx.compose.ui.graphics.toArgb
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.ui.settings.SettingsProvider
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.monoFontBytes
import java.awt.Font
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.ui.graphics.Color as ComposeColor

/** The `t-sm` size from the design system, in points. */
private const val TERMINAL_FONT_SIZE = 12f

private const val TERMINAL_LINE_SPACING = 1.2f

/**
 * The design system's inset palette for the terminal: `bgInset` behind `fg2`, the selection `accentFg` on `accent`.
 * [scale] converts the design's px into Swing units: Swing draws at the screen's own scale while the Compose content renders
 * at the app's content density, so without it the terminal text would come out smaller than the text around it.
 */
class TerminalSettings(private val colors: SkillBillColors, font: Font, scale: Float = 1f) : DefaultSettingsProvider() {
  private val fontSize = TERMINAL_FONT_SIZE * scale
  private val font = font.deriveFont(fontSize)

  override fun getDefaultForeground(): TerminalColor = colors.fg2.terminalColor()

  override fun getDefaultBackground(): TerminalColor = colors.bgInset.terminalColor()

  @Deprecated("Kept because JediTermWidget 3.57 seeds its StyleState from it; derived from the two getters above.")
  override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())

  // JediTerm ignores getSelectionColor while inverse selection is on, which is its default.
  override fun useInverseSelectionColor(): Boolean = false

  override fun getSelectionColor(): TextStyle = TextStyle(colors.accentFg.terminalColor(), colors.accent.terminalColor())

  override fun getTerminalFont(): Font = font

  override fun getTerminalFontSize(): Float = fontSize

  // `.sb-term-out` sets line-height 1.6. JediTerm multiplies the font's own height (about 1.32 em for JetBrains Mono), so
  // 1.2 lands near 19 px rows at 12 px.
  override fun getLineSpacing(): Float = TERMINAL_LINE_SPACING
}

private fun ComposeColor.terminalColor(): TerminalColor {
  val argb = toArgb()
  return TerminalColor((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

/** JetBrains Mono from the design system, or the platform monospaced font when it cannot be read; never blocks the terminal. */
suspend fun terminalFont(): Font = try {
  Font.createFont(Font.TRUETYPE_FONT, monoFontBytes().inputStream()).deriveFont(Font.PLAIN, TERMINAL_FONT_SIZE)
} catch (cancellation: CancellationException) {
  throw cancellation
} catch (_: Exception) {
  Font(Font.MONOSPACED, Font.PLAIN, TERMINAL_FONT_SIZE.toInt())
}

/** The Swing side of one terminal: the component Compose hosts, its focus, and the way to end it. */
interface TerminalView {
  val component: JComponent

  fun requestFocus()

  fun close()
}

/** Builds the widget on the event thread, attaches [process] and reports the terminal panel's focus through [onFocusChanged]. */
fun jediTermView(process: ShellProcess, settings: SettingsProvider, onFocusChanged: (Boolean) -> Unit): TerminalView {
  val widget = JediTermWidget(80, 24, settings)
  try {
    widget.setTtyConnector(process.connector)
    widget.start()
  } catch (failure: Throwable) {
    widget.close()
    throw failure
  }
  widget.terminalPanel.addFocusListener(
    object : FocusListener {
      override fun focusGained(event: FocusEvent) = onFocusChanged(true)

      override fun focusLost(event: FocusEvent) = onFocusChanged(false)
    },
  )
  return object : TerminalView {
    override val component: JComponent = widget

    override fun requestFocus() {
      widget.terminalPanel.requestFocusInWindow()
    }

    override fun close() = widget.close()
  }
}
