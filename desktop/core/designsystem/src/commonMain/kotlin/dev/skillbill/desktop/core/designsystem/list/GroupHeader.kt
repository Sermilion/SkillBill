package dev.skillbill.desktop.core.designsystem.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
fun GroupHeader(
  label: String,
  count: Int,
  modifier: Modifier = Modifier,
  lead: (@Composable () -> Unit)? = null,
  end: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    modifier.fillMaxWidth().height(SkillBillSize.row).background(colors.bgRaised).hairline(Edge.Bottom, colors.borderSubtle)
      .padding(start = 20.dp, end = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    lead?.invoke()
    BasicText(label, Modifier.weight(1f, fill = false), style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    BasicText(count.toString(), style = type.tBase.tabular().copy(color = colors.fgFaint))
    if (end != null) {
      Spacer(Modifier.weight(1f))
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = end)
    }
  }
}

/** Emits a [GroupHeader] that stays pinned while its group's rows scroll. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.groupHeader(
  key: Any,
  label: String,
  count: Int,
  lead: (@Composable () -> Unit)? = null,
  end: (@Composable RowScope.() -> Unit)? = null,
) {
  stickyHeader(key) { GroupHeader(label, count, lead = lead, end = end) }
}
