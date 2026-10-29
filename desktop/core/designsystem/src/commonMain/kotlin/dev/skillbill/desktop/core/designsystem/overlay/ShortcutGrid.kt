package dev.skillbill.desktop.core.designsystem.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd

private val shortcutTwoColumnMinWidth = 360.dp

@Immutable
class ShortcutEntry(val action: String, val keys: String)

@Composable
fun ShortcutGrid(entries: List<ShortcutEntry>, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val columns = if (maxWidth < shortcutTwoColumnMinWidth) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      entries.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
          row.forEach { ShortcutCell(it, Modifier.weight(1f)) }
          repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun ShortcutCell(entry: ShortcutEntry, modifier: Modifier) {
  Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(entry.action, Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Kbd(entry.keys)
  }
}
