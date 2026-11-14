package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

private val PICKER_WIDTH = 560.dp
private val AGENT_PANE_WIDTH = 168.dp
private val PANES_HEIGHT = 380.dp

/** A model row the operator picked. [model] and [effort] replace the agent's own, so a null [model] keeps the agent CLI's default. */
data class ModelPick(val model: String?, val effort: String?, val label: String) {
  fun applyTo(agent: ChatAgent): ChatAgent = agent.copy(model = model, effort = effort)

  companion object {
    val AgentDefault = ModelPick(null, null, "agent default")

    fun listed(model: ChatModel) = ModelPick(model.id, null, model.label)

    fun configured(agent: ChatAgent) = ModelPick(agent.model, agent.effort, agent.model ?: "agent default")
  }
}

/**
 * The agent chip. Its popover lists agents on the left and the highlighted agent's models for [repositoryRoot] on the right.
 * Highlighting an agent only previews its models; picking a model calls [onChoose] once with both, so a switch never needs a
 * second trip. Models load each time an agent is highlighted, showing the stored list while discovery runs.
 */
@Composable
internal fun AgentPicker(
  agent: ChatAgent?,
  agents: List<String>,
  repositoryRoot: String?,
  enabled: Boolean,
  disabledWhy: String? = null,
  resolveAgent: suspend (String) -> ChatAgent,
  loadModels: (ChatAgent, String) -> Flow<ChatModelsRead>,
  onChoose: (agentId: String, pick: ModelPick) -> Unit,
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
      SbPopover(expanded = true, onDismissRequest = close, width = PICKER_WIDTH, maxWidth = PICKER_WIDTH, title = "Agent and model") {
        AgentModelPanes(agent, agents, repositoryRoot, resolveAgent, loadModels) { agentId, pick -> close(); onChoose(agentId, pick) }
      }
    }
  }
}

@Composable
private fun AgentModelPanes(
  current: ChatAgent?,
  agents: List<String>,
  repositoryRoot: String?,
  resolveAgent: suspend (String) -> ChatAgent,
  loadModels: (ChatAgent, String) -> Flow<ChatModelsRead>,
  onChoose: (String, ModelPick) -> Unit,
) {
  val initial = remember { current?.agentId?.takeIf { it in agents } ?: agents.first() }
  var highlighted by remember { mutableStateOf(initial) }
  val rowFocus = remember(agents) { agents.associateWith { FocusRequester() } }
  val searchFocus = remember { FocusRequester() }
  // The popover focuses its first row when it opens; previews wait until focus is back on the current agent.
  var focusPreviews by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    withFrameNanos {}
    runCatching { rowFocus.getValue(initial).requestFocus() }
    focusPreviews = true
  }
  val divider = SkillBillTheme.colors.borderSubtle
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(PANES_HEIGHT)) {
      Column(Modifier.width(AGENT_PANE_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState())) {
        SbMenu(listOf(SbMenuEntry.Head("Agent")))
        agents.forEach { choice ->
          SbMenuItem(
            SbMenuEntry.Item(choice, { highlighted = choice }, checked = choice == current?.agentId, mono = true),
            highlighted = choice == highlighted,
            modifier = Modifier.focusRequester(rowFocus.getValue(choice))
              .onFocusChanged { if (it.isFocused && focusPreviews) highlighted = choice }
              .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown || (it.key != Key.DirectionRight && it.key != Key.Enter)) return@onPreviewKeyEvent false
                highlighted = choice
                runCatching { searchFocus.requestFocus() }
                true
              },
          )
        }
      }
      Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
      key(highlighted, repositoryRoot) {
        ModelPane(
          current, highlighted, repositoryRoot, resolveAgent, loadModels, searchFocus,
          onBack = { runCatching { rowFocus.getValue(highlighted).requestFocus() } },
          onPick = { onChoose(highlighted, it) },
          modifier = Modifier.weight(1f).fillMaxHeight(),
        )
      }
    }
    if (current != null && highlighted != current.agentId) {
      Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
      BasicText(
        "Picking a model switches to $highlighted and starts a new conversation.",
        Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.accentText),
      )
    }
  }
}

@Composable
private fun ModelPane(
  current: ChatAgent?,
  agentId: String,
  repositoryRoot: String?,
  resolveAgent: suspend (String) -> ChatAgent,
  load: (ChatAgent, String) -> Flow<ChatModelsRead>,
  searchFocus: FocusRequester,
  onBack: () -> Unit,
  onPick: (ModelPick) -> Unit,
  modifier: Modifier,
) {
  var configured by remember { mutableStateOf<ChatAgent?>(null) }
  var read by remember { mutableStateOf<ChatModelsRead?>(null) }
  var retry by remember { mutableStateOf(0) }
  var query by remember { mutableStateOf("") }
  var searchFocused by remember { mutableStateOf(false) }
  val active = current?.takeIf { it.agentId == agentId }
  LaunchedEffect(retry) {
    read = null
    val resolved = resolveAgent(agentId)
    configured = resolved.takeIf { it.model != null }
    if (repositoryRoot != null) load(active ?: resolved, repositoryRoot).collect { read = it }
  }
  val configuredChecked = active != null && configured?.let { it.model == active.model && it.effort == active.effort } == true
  Column(
    // Left with an empty search, or Up from the search, returns to the highlighted agent rather than the last agent row.
    modifier.padding(4.dp).onPreviewKeyEvent {
      val back = it.type == KeyEventType.KeyDown &&
        ((it.key == Key.DirectionLeft && query.isEmpty()) || (it.key == Key.DirectionUp && searchFocused))
      if (back) onBack()
      back
    },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SbInput(query, { query = it }, placeholder = "Search $agentId models", modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { searchFocused = it.hasFocus }
        .gatesShortcutsWhileFocused())
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      configured?.let { matrix ->
        SbMenuItem(
          SbMenuEntry.Item("Configured", { onPick(ModelPick.configured(matrix)) }, checked = configuredChecked,
            detail = listOfNotNull(matrix.model, matrix.effort).joinToString(" · ")),
        )
      }
      SbMenuItem(SbMenuEntry.Item("Agent default", { onPick(ModelPick.AgentDefault) }, checked = active != null && active.model == null))
      SbMenu(listOf(SbMenuEntry.Separator))
      when (val result = read) {
        null -> MutedText(if (repositoryRoot == null) "Choose a repository to list models." else "Loading models...")
        is ChatModelsRead.Failed -> {
          MutedText(result.message)
          SbButton("Retry", { retry++ }, size = SbControlSize.Small)
        }
        is ChatModelsRead.Loaded -> ListedModels(result.models, query, active, configuredChecked, onPick)
        is ChatModelsRead.Cached -> {
          ListedModels(result.models, query, active, configuredChecked, onPick)
          result.refreshFailure?.let { MutedText(it) }
        }
      }
    }
    if (read is ChatModelsRead.Loaded || read is ChatModelsRead.Cached) {
      SbButton("Refresh models", { retry++ }, size = SbControlSize.Small)
    }
  }
}

@Composable
private fun ListedModels(
  models: List<ChatModel>,
  query: String,
  active: ChatAgent?,
  configuredChecked: Boolean,
  onPick: (ModelPick) -> Unit,
) {
  val shown = models.filter { it.id.contains(query, true) || it.label.contains(query, true) }
  shown.forEach { model ->
    SbMenuItem(
      SbMenuEntry.Item(model.label, { onPick(ModelPick.listed(model)) },
        checked = active != null && model.id == active.model && !configuredChecked,
        detail = model.id.takeUnless { it == model.label }),
    )
  }
  if (shown.isEmpty()) MutedText(if (models.isEmpty()) "No models reported by this agent." else "No matching models.")
}

@Composable
private fun MutedText(text: String) {
  BasicText(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
}

internal fun chipText(agent: ChatAgent?): String = when {
  agent == null -> "No agent"
  agent.model == null -> "${agentName(agent)} · default"
  agent.effort == null -> "${agentName(agent)} · ${agent.model}"
  else -> "${agentName(agent)} · ${agent.model} · ${agent.effort}"
}
