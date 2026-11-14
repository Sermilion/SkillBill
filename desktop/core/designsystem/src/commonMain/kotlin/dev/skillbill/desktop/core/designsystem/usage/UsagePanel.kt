package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun UsagePanel(
  state: UsageUiState,
  modifier: Modifier = Modifier,
  headerAction: @Composable () -> Unit = {},
  providerControls: @Composable () -> Unit = {},
  settingsContent: @Composable () -> Unit = {},
  footerContent: @Composable () -> Unit = {},
) {
  val colors = SkillBillTheme.colors
  Column(modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      BasicText("Usage limits", style = SkillBillTheme.type.tMd.copy(color = colors.fg))
      headerAction()
    }
    providerControls()
    if (state.nameOnly) {
      // Nothing to measure, so the settings follow the provider controls directly.
      Spacer(Modifier.height(12.dp))
    } else {
      // Only as tall as its content, so a one-line message leaves no blank block.
      Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (state.meters.isEmpty()) {
          state.message?.let { BasicText(it, style = usageText().copy(color = colors.fgMuted)) }
        } else {
          state.meters.forEach { meter -> key(meter.id) { UsageMeterRow(meter, detailed = true) } }
          state.message?.let { BasicText(it, style = usageText().copy(color = colors.fgMuted)) }
        }
      }
    }
    settingsContent()
    // A name-only agent has no usage actions, so the panel ends after the settings.
    if (!state.nameOnly) {
      Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
      Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        footerContent()
      }
    }
  }
}
