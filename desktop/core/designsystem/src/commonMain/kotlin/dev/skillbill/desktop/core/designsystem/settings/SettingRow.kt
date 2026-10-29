package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

private val settingTextMinWidth = 220.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingRow(
  modifier: Modifier = Modifier,
  label: String? = null,
  description: String? = null,
  stacked: Boolean = false,
  control: (@Composable RowScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val text: @Composable (Modifier) -> Unit = { textModifier ->
    Column(textModifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
      label?.let { BasicText(it, style = type.tBaseMedium.copy(color = colors.fg)) }
      description?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.45.em)) }
    }
  }
  val controls: @Composable () -> Unit = {
    control?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
  }
  val padded = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
  if (stacked) {
    Column(padded, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      text(Modifier.fillMaxWidth())
      controls()
    }
  } else {
    FlowRow(padded, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.CenterVertically) {
      text(Modifier.weight(1f).widthIn(min = settingTextMinWidth))
      controls()
    }
  }
}
