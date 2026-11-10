package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbMenuItem
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.model.ChatModelsRead
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/**
 * The agent chip. Clicking it lists the agents the chat can switch to, then the models the current agent reports for
 * [repositoryRoot]. Models load each time the popover opens; choosing "Agent default" clears the explicit model.
 */
@Composable
internal fun AgentPicker(
  agent: ChatAgent?,
  agents: List<String>,
  repositoryRoot: String?,
  enabled: Boolean,
  disabledWhy: String? = null,
  loadModels: suspend (ChatAgent, String) -> ChatModelsRead,
  onChooseModel: (ChatModel?) -> Unit,
  onChoose: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val close = { open = false }
  val canOpen = enabled && agents.isNotEmpty()
  val why = disabledWhy.takeIf { !canOpen }
  Box {
    val chipModifier = Modifier.then(if (canOpen) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(enabled = canOpen, onClickLabel = "Change agent or model") { open = true }
      .then(if (why != null) Modifier.semantics { stateDescription = why } else Modifier)
    val chip = @Composable {
      SbLabel(
        chipText(agent),
        style = SbLabelStyle.Mono,
        icon = LucideIcons.cpu,
        maxWidth = 220.dp,
        modifier = chipModifier,
      )
    }
    if (why != null) SbTooltip(why) { chip() } else chip()
    if (open && canOpen) {
      ShellLayer(close)
      SbPopover(expanded = true, onDismissRequest = close) {
        Column(Modifier.width(360.dp)) {
          SbMenu(
            listOf<SbMenuEntry>(SbMenuEntry.Head("Agent")) +
              agents.map { choice ->
                SbMenuEntry.Item(choice, { close(); onChoose(choice) }, checked = choice == agent?.agentId, mono = true)
              } +
              listOf(SbMenuEntry.Separator, SbMenuEntry.Head("Model")),
          )
          if (agent != null) {
            ModelSection(agent, repositoryRoot, loadModels) { close(); onChooseModel(it) }
          }
        }
      }
    }
  }
}

@Composable
private fun ModelSection(
  agent: ChatAgent,
  repositoryRoot: String?,
  load: suspend (ChatAgent, String) -> ChatModelsRead,
  onChoose: (ChatModel?) -> Unit,
) {
  var read by remember(agent, repositoryRoot) { mutableStateOf<ChatModelsRead?>(null) }
  var retry by remember { mutableStateOf(0) }
  var query by remember { mutableStateOf("") }
  if (repositoryRoot != null) {
    LaunchedEffect(agent, repositoryRoot, retry) {
      read = null
      read = load(agent, repositoryRoot)
    }
  }
  Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SbInput(query, { query = it }, placeholder = "Search models", modifier = Modifier.gatesShortcutsWhileFocused())
    SbMenuItem(SbMenuEntry.Item("Agent default", { onChoose(null) }, checked = agent.model == null))
    when (val result = read) {
      null -> MutedText(if (repositoryRoot == null) "Choose a repository to list models." else "Loading models...")
      is ChatModelsRead.Failed -> {
        MutedText(result.message)
        SbButton("Retry", { retry++ }, size = SbControlSize.Small)
      }
      is ChatModelsRead.Loaded -> {
        val models = result.models.filter { it.id.contains(query, true) || it.label.contains(query, true) }
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
          models.forEach { model ->
            SbMenuItem(
              SbMenuEntry.Item(model.label, { onChoose(model) }, checked = model.id == agent.model,
                detail = model.id.takeUnless { it == model.label }),
            )
          }
          if (models.isEmpty()) MutedText(if (result.models.isEmpty()) "No models reported by this agent." else "No matching models.")
        }
        SbButton("Refresh models", { retry++ }, size = SbControlSize.Small)
      }
    }
  }
}

@Composable
private fun MutedText(text: String) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
}

internal fun chipText(agent: ChatAgent?): String = when {
  agent == null -> "No agent"
  agent.model == null -> "${agentName(agent)} · default"
  agent.effort == null -> "${agentName(agent)} · ${agent.model}"
  else -> "${agentName(agent)} · ${agent.model} · ${agent.effort}"
}
