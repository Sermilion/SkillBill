package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

/**
 * The active chat's input. Enter sends and Shift+Enter starts a new line; "Pause and act" shows only while the execution runs.
 * The input stays editable while a turn runs. Submitting queues a message until the current turn ends.
 * A draft of `/resume` or `/clear` is a command: it shows highlighted and runs even while a turn runs. `/resume` opens the
 * saved-chat picker; `/clear` ends the running turn and starts a new conversation.
 */
@Composable
internal fun ChatComposer(session: ChatSession, workspace: ChatWorkspace) {
  val busy by session.busy.collectAsState()
  val agent by session.agent.collectAsState()
  val supported by session.agentSupported.collectAsState()
  val gone by session.gone.collectAsState()
  val mode by session.mode.collectAsState()
  val choices by session.resumeChoices.collectAsState()
  val queued by session.queued.collectAsState()
  val focus = remember(session) { FocusRequester() }
  LaunchedEffect(session) { focus.requestFocus() }
  val id = session.id
  val text = workspace.drafts[id].orEmpty()
  val command = text.trim().takeIf { it in CHAT_COMMANDS }
  val ready = command != null || (supported && !gone && text.isNotBlank())
  fun run(command: String) {
    workspace.drafts.remove(id)
    when (command) {
      RESUME_COMMAND -> workspace.showResumable(id)
      CLEAR_COMMAND -> workspace.clear(id)
    }
  }
  fun submit(action: (String, String) -> Unit) {
    if (!ready) return
    if (command != null) return run(command)
    action(id, text)
    workspace.drafts.remove(id)
    focus.requestFocus()
  }
  Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ChatActivityPill(session.thread, gone)
    if (command == null && commandTokenLength(text) == text.length) {
      matchingCommands(text).forEach { CommandHint(it) { run(it) } }
    }
    Box(Modifier.fillMaxWidth()) {
      SbInput(
        text, { workspace.drafts[id] = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focus).gatesShortcutsWhileFocused(),
        placeholder = "Ask about this execution, or type $RESUME_COMMAND or $CLEAR_COMMAND",
        enabled = !gone,
        onSubmit = { submit(workspace::send) },
        multiline = true,
        visualTransformation = CommandHighlight(SkillBillTheme.colors),
      )
      choices?.let { saved ->
        ResumePicker(saved, workspace.now(), { workspace.resume(id, it) }, { workspace.dismissResume(id) })
      }
    }
    if (queued.isNotEmpty()) SbLabel("${queued.size} ${if (queued.size == 1) "message" else "messages"} queued")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      ModeBadge(mode)
      AgentPicker(agent, workspace.agents, enabled = !busy && !gone) { workspace.switchAgent(id, it) }
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        if (mode == ChatPermissionMode.ReadOnly) {
          SbButton(
            if (busy) "Queue pause and act" else "Pause and act", { submit(workspace::pauseAndAct) },
            size = SbControlSize.Small, enabled = ready && command == null,
            tooltip = "Pauses the execution when this message runs, acts, then resumes it",
          )
        }
        SbButton(if (busy && command == null) "Queue" else "Send", { submit(workspace::send) }, variant = SbButtonVariant.Primary, size = SbControlSize.Small, enabled = ready, shortcut = "↵")
      }
    }
  }
}

/** The agent chip; clicking it lists the agents the chat can switch to. */
@Composable
internal fun AgentPicker(agent: ChatAgent?, agents: List<String>, enabled: Boolean, disabledWhy: String? = null, onChoose: (String) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val close = { open = false }
  val canOpen = enabled && agents.isNotEmpty()
  val why = disabledWhy.takeIf { !canOpen }
  Box {
    val chipModifier = Modifier.then(if (canOpen) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
      .clickable(enabled = canOpen, onClickLabel = "Change agent") { open = true }
      .then(if (why != null) Modifier.semantics { stateDescription = why } else Modifier)
    val chip = @Composable {
      ChatPill(chipText(agent), mono = true, icon = LucideIcons.cpu, maxWidth = 220.dp, modifier = chipModifier)
    }
    if (why != null) SbTooltip(why) { chip() } else chip()
    if (open && canOpen) {
      ShellLayer(close)
      SbPopover(expanded = true, onDismissRequest = close) {
        SbMenu(
          agents.map { choice ->
            SbMenuEntry.Item(choice, { close(); onChoose(choice) }, checked = choice == agent?.agentId, mono = true)
          },
        )
      }
    }
  }
}

internal fun chipText(agent: ChatAgent?): String = when {
  agent == null -> "No agent"
  agent.model == null -> "${agentName(agent)} · default"
  agent.effort == null -> "${agentName(agent)} · ${agent.model}"
  else -> "${agentName(agent)} · ${agent.model} · ${agent.effort}"
}
