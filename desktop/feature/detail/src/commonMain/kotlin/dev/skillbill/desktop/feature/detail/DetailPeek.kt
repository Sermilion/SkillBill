package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.shell.FloatingPane

@Composable
internal fun DetailPeek(
  open: Boolean,
  wide: Boolean,
  requestedWidthDp: Int?,
  onResize: (Int) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  val colors = SkillBillTheme.colors
  FloatingPane(
    open = open,
    title = "Details",
    requestedWidthDp = requestedWidthDp,
    defaultWidth = if (wide) 800.dp else 560.dp,
    onResize = onResize,
    onReset = onReset,
    onDismiss = onDismiss,
  ) {
    Column(Modifier.fillMaxHeight()) {
      Row(Modifier.height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("Details", Modifier.weight(1f), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
        SbIconButton(LucideIcons.x, "Close details", onDismiss)
      }
      Box(Modifier.weight(1f)) { content() }
    }
  }
}

internal fun detailsPaneWide(item: dev.skillbill.desktop.core.domain.model.WorkItem): Boolean =
  item.kind == dev.skillbill.desktop.core.domain.model.WorkKind.Goal
