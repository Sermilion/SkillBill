package dev.skillbill.tray.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.ui.ExpandableReason

@Composable
internal fun QaReportSection(text: String) {
  if (text.isBlank()) return
  ExpandableReason(text, "QA report", color = ink, fontSize = 12.sp, lineHeight = 18.sp)
}
