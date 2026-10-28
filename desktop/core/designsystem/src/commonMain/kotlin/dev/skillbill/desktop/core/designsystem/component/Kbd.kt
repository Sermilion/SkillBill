package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private val KEY_CAP_SIZE = 17.dp
private val KEY_CAP_GAP = 2.dp
private val KEY_CAP_PADDING = 4.dp

@Composable
fun Kbd(keys: List<String>, onAccent: Boolean = false) {
  val colors = SkillBillTheme.colors
  Row(
    Modifier.semantics { contentDescription = keys.joinToString(" ") },
    horizontalArrangement = Arrangement.spacedBy(KEY_CAP_GAP),
  ) {
    keys.forEach { key ->
      val shape = RoundedCornerShape(SkillBillRadius.xs)
      Box(
        Modifier.height(KEY_CAP_SIZE).defaultMinSize(minWidth = KEY_CAP_SIZE)
          .then(if (onAccent) Modifier.background(colors.accentFg.copy(alpha = 0.12f), shape)
          else Modifier.background(colors.bgRaised, shape).border(1.dp, colors.border, shape))
          .padding(horizontal = KEY_CAP_PADDING),
        contentAlignment = Alignment.Center,
      ) {
        BasicText(
          key,
          style = SkillBillTheme.type.t2xs.copy(
            color = if (onAccent) colors.accentFg else colors.fgMuted,
            fontFamily = SkillBillTheme.type.tXs.fontFamily,
            fontWeight = FontWeight.Medium,
          ),
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
fun Kbd(keys: String) = Kbd(keys.split(' ').filter(String::isNotBlank))
