package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline

/** [separated] draws the top rule and spacing between stacked sections; the pane's first section passes false. */
@Composable
fun PaneSection(
  modifier: Modifier = Modifier,
  title: String? = null,
  end: (@Composable () -> Unit)? = null,
  separated: Boolean = true,
  body: @Composable ColumnScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  Column(
    modifier.fillMaxWidth().then(if (separated) Modifier.hairline(Edge.Top, colors.borderSubtle).padding(top = 16.dp) else Modifier),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (title != null || end != null) {
      Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(title.orEmpty(), style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Medium))
        end?.invoke()
      }
    }
    body()
  }
}
