package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

/** `.sb-id`: 500 12px/1 sans with tabular figures, fg-faint. */
@Composable
fun IdTag(id: String, modifier: Modifier = Modifier) {
  BasicText(id, modifier, style = SkillBillTheme.type.tSmMedium.tabular().copy(color = SkillBillTheme.colors.fgFaint, lineHeight = 1.em), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}
