package dev.skillbill.desktop.core.designsystem.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      state.contextLabel?.let { BasicText("Following $it", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(state.provider, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
        state.account.takeIf(String::isNotBlank)?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
        state.accountExplanation?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(state.status, style = SkillBillTheme.type.tXs.copy(color = colors.fg2))
        state.reason?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
        if (state.checking) BasicText("Checking for updates", style = SkillBillTheme.type.tXs.copy(color = colors.fg2))
        if (state.stale) BasicText("Stale data", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
        if (state.expired) BasicText("Expired", style = SkillBillTheme.type.tXs.copy(color = colors.stFailed))
        if (state.sessionOnly) BasicText("Updated by this session", style = SkillBillTheme.type.tXs.copy(color = colors.fg2))
        state.source?.let { BasicText("Source: $it", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
        state.observationAge?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
        state.requestPermission?.let { BasicText("Request permission: $it", style = SkillBillTheme.type.tXs.copy(color = colors.fg2)) }
      }
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.detailMeters.forEach { meter -> key(meter.id) { UsageMeterRow(meter) } }
      }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().background(colors.borderSubtle).padding(top = 1.dp))
    Column(
      Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
      horizontalAlignment = Alignment.End,
    ) {
      footerContent()
    }
  }
}
