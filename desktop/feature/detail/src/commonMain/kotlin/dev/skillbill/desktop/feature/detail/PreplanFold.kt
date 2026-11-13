package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

@Composable
internal fun PreplanFold(title: String, preview: String, body: @Composable () -> Unit) {
  var open by rememberSaveable(title, preview) { mutableStateOf(false) }
  VerifyFold(title, open, { open = !open }, note = preview) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreplanAddonLabels(addons: List<String>) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    addons.forEach { SbLabel(laneChipLabel(it), tooltip = it) }
  }
}

@Composable
internal fun PreplanHistoryEvidence(text: String, detail: Detail, item: WorkItem?, onAction: (DetailAction) -> Unit) {
  ReportText(text)
  val paths = Regex("(?:[a-zA-Z0-9_.-]+/)*agent/(?:history|decisions)\\.md").findAll(text).map { it.value }.distinct().toList()
  paths.forEach { path ->
    val resolved = item?.let { resolveRecordedReceiptPath(path, detail, it) }
    PeekOutput(
      LucideIcons.history, fileName(path), tooltip = path,
      onClick = resolved?.takeIf { it.openable }?.let { { onAction(DetailAction.OpenSpecFile(it.absolute)) } },
    )
  }
}
