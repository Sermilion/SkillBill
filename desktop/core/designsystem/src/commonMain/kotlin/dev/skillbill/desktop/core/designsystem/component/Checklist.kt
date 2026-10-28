package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

data class ChecklistItem(val id: String, val text: String, val checked: Boolean)

@Composable
fun Checklist(items: List<ChecklistItem>, onCheckedChange: (id: String, checked: Boolean) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    items.forEach { item ->
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SbCheckbox(item.checked, { onCheckedChange(item.id, it) }, item.text, modifier = Modifier.padding(top = 2.dp))
        BasicText(item.text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
    }
  }
}
