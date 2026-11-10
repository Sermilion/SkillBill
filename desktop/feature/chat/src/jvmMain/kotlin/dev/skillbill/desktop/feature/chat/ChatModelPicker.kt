package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbMenuItem
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

@Composable
internal fun ChatModelPicker(
  agent: ChatAgent?,
  repositoryRoot: String?,
  enabled: Boolean,
  load: suspend (ChatAgent, String) -> ChatModelsRead,
  onChoose: (ChatModel?) -> Unit,
) {
  var open by remember(agent, repositoryRoot) { mutableStateOf(false) }
  var read by remember(agent, repositoryRoot) { mutableStateOf<ChatModelsRead?>(null) }
  var retry by remember { mutableStateOf(0) }
  var query by remember(open) { mutableStateOf("") }
  val close = { open = false }
  Box {
    SbButton("Model: ${agent?.model ?: "default"}", { open = true }, size = SbControlSize.Small, modifier = Modifier.widthIn(max = 200.dp), tooltip = agent?.model,
      enabled = enabled && agent != null && repositoryRoot != null)
    if (open && enabled && agent != null && repositoryRoot != null) {
      ShellLayer(close)
      LaunchedEffect(agent, repositoryRoot, retry) {
        read = null
        read = load(agent, repositoryRoot)
      }
      SbPopover(expanded = true, onDismissRequest = close, placement = SbPopoverPlacement.Above) {
        Column(Modifier.width(360.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          SbInput(query, { query = it }, placeholder = "Search models", modifier = Modifier.gatesShortcutsWhileFocused())
          SbMenuItem(SbMenuEntry.Item("Agent default", { close(); onChoose(null) }, checked = agent.model == null))
          when (val result = read) {
            null -> BasicText("Loading models...", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
            is ChatModelsRead.Failed -> {
              BasicText(result.message, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
              SbButton("Retry", { retry++ }, size = SbControlSize.Small)
            }
            is ChatModelsRead.Loaded -> {
              val models = result.models.filter { it.id.contains(query, true) || it.label.contains(query, true) }
              Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                models.forEach { model ->
                  SbMenuItem(SbMenuEntry.Item(model.label, { close(); onChoose(model) }, checked = model.id == agent.model,
                    detail = model.id.takeUnless { it == model.label }))
                }
                if (models.isEmpty()) BasicText(if (result.models.isEmpty()) "No models reported by this agent." else "No matching models.",
                  style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
              }
              SbButton("Refresh models", { retry++ }, size = SbControlSize.Small)
            }
          }
        }
      }
    }
  }
}
