package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
  FloatingPane(
    open = open,
    title = "Details",
    requestedWidthDp = requestedWidthDp,
    defaultWidth = if (wide) 800.dp else 560.dp,
    onResize = onResize,
    onReset = onReset,
    onDismiss = onDismiss,
    modal = false,
  ) {
    Box(Modifier.fillMaxHeight()) { content() }
  }
}

internal fun detailsPaneWide(item: dev.skillbill.desktop.core.domain.model.WorkItem): Boolean =
  item.kind == dev.skillbill.desktop.core.domain.model.WorkKind.Goal
