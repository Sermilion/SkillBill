package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
fun Ledger(leftTitle: String, left: List<String>, rightTitle: String, right: List<String>) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    LedgerColumn(leftTitle, left, true, Modifier.weight(1f))
    LedgerColumn(rightTitle, right, false, Modifier.weight(1f))
  }
}

@Composable
private fun LedgerColumn(title: String, items: List<String>, deleted: Boolean, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(title, Modifier, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
    items.forEach { item ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides if (deleted) SkillBillTheme.colors.stFailed else SkillBillTheme.colors.stDone) {
          SbIcon(if (deleted) LucideIcons.trash else LucideIcons.check, SbIconSize.Sm)
        }
        BasicText(item, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
    }
  }
}
