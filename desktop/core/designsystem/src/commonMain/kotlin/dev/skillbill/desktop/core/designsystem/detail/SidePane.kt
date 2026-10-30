package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline

/**
 * [fillWidth] makes the pane take the whole window; the shell passes it on narrow windows. [showStartBorder] draws the left
 * hairline; `.sb-sidepane` drops it below 760 px, where the pane covers the panel, so it defaults to off with [fillWidth].
 */
@Composable
fun SidePane(
  title: String,
  modifier: Modifier = Modifier,
  head: (@Composable RowScope.() -> Unit)? = null,
  actions: @Composable RowScope.() -> Unit = {},
  fillWidth: Boolean = false,
  showStartBorder: Boolean = !fillWidth,
  body: @Composable ColumnScope.() -> Unit,
) {
  val colors = SkillBillTheme.colors
  Column(
    modifier.then(if (fillWidth) Modifier.fillMaxSize() else Modifier.width(SkillBillSize.specsPane).fillMaxHeight())
      .background(colors.bgPanel).then(if (showStartBorder) Modifier.hairline(Edge.Start, colors.borderSubtle) else Modifier).semantics { paneTitle = title },
  ) {
    Row(
      Modifier.fillMaxWidth().height(SkillBillSize.header).hairline(Edge.Bottom, colors.borderSubtle).padding(start = 16.dp, end = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Title and head share the room left of the actions, so a short title never pulls the actions off the right edge.
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, Modifier.weight(1f, fill = false), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        head?.invoke(this)
      }
      actions()
    }
    Column(Modifier.fillMaxWidth().weight(1f), content = body)
  }
}
