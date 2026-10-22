package dev.skillbill.tray.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.ink

/** The prose report a QA run stored, shown verbatim and selectable. */
@Composable
internal fun QaReportSection(text: String) {
  if (text.isBlank()) return
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SelectionContainer {
      Text(text, color = ink, fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp)
    }
  }
}
