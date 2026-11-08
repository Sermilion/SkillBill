package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
fun GroupLabel(
  label: String,
  modifier: Modifier = Modifier,
  count: String? = null,
  lead: (@Composable () -> Unit)? = null,
  end: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val labelStyle = SkillBillTheme.type.tXs.copy(
    color = colors.fgFaint,
    fontWeight = FontWeight.Medium,
    lineHeight = 1.em,
  )
  Row(
    modifier.fillMaxWidth().height(24.dp).semantics { heading() },
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    lead?.invoke()
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      BasicText(
        label,
        Modifier.weight(1f, fill = false),
        style = labelStyle,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
      )
      count?.let { BasicText(it, style = labelStyle.tabular(), maxLines = 1, softWrap = false) }
    }
    end?.invoke(this)
  }
}
