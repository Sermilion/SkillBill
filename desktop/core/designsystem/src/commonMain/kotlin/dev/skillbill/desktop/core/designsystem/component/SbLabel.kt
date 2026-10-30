package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

/** `Label({ dot: false })` is [Plain]; `Dot(null)` keeps the fg-faint dot that `Label` draws when no color is given. */
sealed interface SbLabelStyle {
  data object Plain : SbLabelStyle
  data class Dot(val color: Color? = null) : SbLabelStyle
  data class Tint(val color: Color) : SbLabelStyle
  data object Mono : SbLabelStyle
}

@Composable
fun SbLabel(text: String, style: SbLabelStyle = SbLabelStyle.Plain, icon: ImageVector? = null, tooltip: String? = null) {
  val colors = SkillBillTheme.colors
  val tint = (style as? SbLabelStyle.Tint)?.color
  val shape = SkillBillRadius.full
  val content: @Composable () -> Unit = { Row(
    Modifier.height(22.dp).background(tint?.copy(alpha = .10f) ?: Color.Transparent, shape)
      .border(1.dp, tint?.copy(alpha = .35f) ?: colors.border, shape)
      // `.sb-label` pads 8px inside its 1px border.
      .padding(horizontal = 9.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    when (style) {
      is SbLabelStyle.Dot -> androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(style.color ?: colors.fgFaint, CircleShape))
      is SbLabelStyle.Tint, SbLabelStyle.Mono, SbLabelStyle.Plain -> Unit
    }
    CompositionLocalProvider(LocalContentColor provides (tint ?: colors.fg2)) {
      icon?.let { SbIcon(it, SbIconSize.Xs) }
      BasicText(text, style = (if (style == SbLabelStyle.Mono) SkillBillTheme.type.t2xs else SkillBillTheme.type.tXs).copy(color = tint ?: colors.fg2, lineHeight = 1.em), maxLines = 1, softWrap = false)
    }
  } }
  if (tooltip != null) SbTooltip(tooltip) { content() } else content()
}
