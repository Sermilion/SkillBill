package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon

fun resultRowLocationLabel(location: String): String =
  location.substringAfterLast('/').substringBefore(':')

@Composable
fun ResultRowBadge(text: String, color: Color) {
  Box(
    Modifier.height(18.dp).background(color.copy(alpha = .14f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp),
    contentAlignment = Alignment.Center,
  ) {
    BasicText(
      text.uppercase(),
      style = SkillBillTheme.type.t2xs.copy(color = color, letterSpacing = .03.em, lineHeight = 1.em),
    )
  }
}

@Composable
fun ResultRowChevron(open: Boolean) {
  CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgFaint) {
    SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
  }
}

@Composable
fun ResultRowFileChip(text: String, tooltip: String = text, icon: ImageVector? = null) {
  val colors = SkillBillTheme.colors
  SbTooltip(tooltip) {
    Row(
      Modifier.widthIn(max = 260.dp).height(22.dp)
        .background(colors.bgRaised, RoundedCornerShape(6.dp))
        .border(1.dp, colors.border, RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (icon != null) CompositionLocalProvider(LocalContentColor provides colors.fg2) { SbIcon(icon, 12.dp) }
      BasicText(
        text,
        style = SkillBillTheme.type.t2xs.copy(color = colors.fg2, fontWeight = FontWeight.Normal, lineHeight = 1.em),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
