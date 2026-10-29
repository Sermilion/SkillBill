package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

@Composable
fun SubtaskRow(
  number: String,
  status: StatusKind,
  progress: Float?,
  title: String,
  modifier: Modifier = Modifier,
  current: Boolean = false,
  meta: @Composable RowScope.() -> Unit = {},
  divider: Boolean = true,
  body: (@Composable ColumnScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val pending = status == StatusKind.Pending
  Column(
    modifier.fillMaxWidth().background(if (current) colors.bgSelected else Color.Transparent)
      .then(if (divider) Modifier.hairline(Edge.Bottom, colors.borderSubtle) else Modifier),
  ) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = SkillBillSize.row).padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.width(20.dp)) { IdTag(number) }
      StatusIcon(status, progress)
      BasicText(
        title, Modifier.weight(1f),
        style = if (pending) type.tBase.copy(color = colors.fgMuted) else type.tBaseMedium.copy(color = colors.fg),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = meta)
    }
    if (body != null) {
      Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
    }
  }
}
