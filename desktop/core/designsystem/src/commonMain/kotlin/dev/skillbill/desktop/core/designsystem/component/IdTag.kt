package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
fun IdTag(id: String) {
  BasicText(id, Modifier, style = SkillBillTheme.type.monoSm.tabular().copy(color = SkillBillTheme.colors.fgFaint), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}
