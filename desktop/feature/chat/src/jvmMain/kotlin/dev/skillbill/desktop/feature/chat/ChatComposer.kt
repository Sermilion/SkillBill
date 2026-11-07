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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.feature.chat.newtask.IssuePrefixPicker
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The active chat's input. Enter sends and Shift+Enter starts a new line; "Pause and act" shows only while the execution runs.
 * Stop ends the running reply and returns queued messages to the draft; Send now ends the reply and sends the queue as one turn.
 * The input stays editable while a turn runs. Submitting queues a message until the current turn ends.
 * A draft of `/resume` or `/clear` is a command: it shows highlighted and runs even while a turn runs. `/resume` opens the
 * saved-chat picker; `/clear` ends the running turn and starts a new conversation.
 * A pasted image is encoded on [encodeContext], off the UI thread by default.
 */
@Composable
internal fun ChatComposer(
  session: ChatSession, workspace: ChatWorkspace, clipboard: ClipboardImageReader = SystemClipboardImageReader,
  encodeContext: CoroutineContext = Dispatchers.Default,
) {
  val busy by session.busy.collectAsState()
  val stoppable by session.stoppable.collectAsState()
  val agent by session.agent.collectAsState()
  val supported by session.agentSupported.collectAsState()
  val gone by session.gone.collectAsState()
  val mode by session.mode.collectAsState()
  val id = session.id
  val cursorMcpDependency = workspace.cursorMcpDependencies[id] ?: CursorMcpDependency.Unspecified
  val choices by session.resumeChoices.collectAsState()
  val queued by session.queued.collectAsState()
  val prefixChoice by session.prefixChoice.collectAsState()
  val focus = remember(session) { FocusRequester() }
  val gate = remember(session) { SendNowGate() }
  LaunchedEffect(session) { focus.requestFocus() }
  val text = workspace.drafts[id].orEmpty()
  val command = text.trim().takeIf { it in CHAT_COMMANDS }
  val images = workspace.pastedImages[id].orEmpty()
  val scope = rememberCoroutineScope()
  val ready = command != null || (supported && !gone && (text.isNotBlank() || images.isNotEmpty()))
  val sendNow = stoppable && queued.isNotEmpty() && text.isBlank() && supported && !gone
  fun run(command: String) {
    workspace.drafts.remove(id)
    when (command) {
      RESUME_COMMAND -> workspace.showResumable(id)
      CLEAR_COMMAND -> workspace.clear(id)
    }
  }
  fun submit(action: (String, String, List<ChatImage>, CursorMcpDependency) -> Unit) {
    if (!ready) return
    if (command != null) return run(command)
    action(id, text, images, cursorMcpDependency)
    workspace.drafts.remove(id)
    workspace.pastedImages.remove(id)
    focus.requestFocus()
  }
  fun press(viaEnter: Boolean) {
    if (sendNow) {
      if (gate.admit(workspace.now(), viaEnter)) {
        workspace.sendNow(id)
        focus.requestFocus()
      }
      return
    }
    submit { chatId, message, pastedImages, dependency -> workspace.send(chatId, message, pastedImages, dependency) }
    gate.pressed(workspace.now(), viaEnter)
  }
  // Returns true, consuming the key, only when the clipboard holds an image; a text paste falls through to the field.
  fun pasteImage(event: KeyEvent): Boolean {
    if (!event.isPasteShortcut() || !supported || gone) return false
    val pasted = clipboard.read() ?: return false
    scope.launch {
      val image = try {
        withContext(encodeContext) { normalizedChatImage(pasted) }
      } catch (_: IOException) {
        null
      }
      if (image != null) {
        workspace.cursorMcpDependencies[id] = CursorMcpDependency.Unspecified
        workspace.addPastedImage(id, image)
      }
    }
    return true
  }
  Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ChatActivityPill(session.thread, gone)
    prefixChoice?.takeIf { !gone }?.let { pending ->
      IssuePrefixPicker(pending.choices, enabled = !busy) { workspace.choosePrefix(id, it) }
    }
    if (command == null && commandTokenLength(text) == text.length) {
      matchingCommands(text).forEach { CommandHint(it) { run(it) } }
    }
    Box(Modifier.fillMaxWidth()) {
      SbInput(
        text, {
          if (workspace.drafts[id] != it) {
            workspace.cursorMcpDependencies[id] = CursorMcpDependency.Unspecified
          }
          workspace.drafts[id] = it
        },
        modifier = Modifier.fillMaxWidth()
          .onPreviewKeyEvent { event ->
            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) gate.enterReleased()
            pasteImage(event)
          }
          .focusRequester(focus)
          .gatesShortcutsWhileFocused(),
        placeholder = "Ask about this execution, or type $RESUME_COMMAND or $CLEAR_COMMAND",
        enabled = !gone,
        onSubmit = { press(viaEnter = true) },
        multiline = true,
        visualTransformation = CommandHighlight(SkillBillTheme.colors),
      )
      choices?.let { saved ->
        ResumePalette(
          saved, workspace.now(), ResumeScope(session.item.branch ?: File(session.item.repositoryRoot).name, worktree = true),
          { session.item.branch }, focus, { workspace.resume(id, it) }, { workspace.dismissResume(id) },
        )
      }
    }
    if (images.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // The remove control leaves with its chip, so focus returns to the input for the next paste.
        images.forEachIndexed { index, _ ->
          ImageChip(index + 1, onRemove = {
            workspace.removePastedImage(id, index)
            focus.requestFocus()
          })
        }
      }
    }
    if (queued.isNotEmpty()) SbLabel("${queued.size} ${if (queued.size == 1) "message" else "messages"} queued")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      ModeBadge(mode)
      AgentPicker(agent, workspace.agents, enabled = !busy && !gone) { workspace.switchAgent(id, it) }
      if (agent?.agentId?.startsWith("cursor", ignoreCase = true) == true) {
        CursorMcpPicker(cursorMcpDependency, enabled = !gone) {
          workspace.cursorMcpDependencies[id] = it
        }
      }
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        if (stoppable) {
          SbButton(
            "Stop", { workspace.stop(id); focus.requestFocus() },
            size = SbControlSize.Small,
            tooltip = "Ends this reply. Queued messages return to the draft",
          )
        }
        if (mode == ChatPermissionMode.ReadOnly) {
          SbButton(
            if (busy) "Queue pause and act" else "Pause and act",
            { submit { chatId, message, pastedImages, dependency ->
              workspace.pauseAndAct(chatId, message, pastedImages, dependency)
            } },
            size = SbControlSize.Small, enabled = ready && command == null,
            tooltip = "Pauses the execution when this message runs, acts, then resumes it",
          )
        }
        SbButton(
          label = when {
            command != null -> "Send"
            sendNow -> "Send now"
            busy -> "Queue"
            else -> "Send"
          },
          onClick = { press(viaEnter = false) },
          variant = SbButtonVariant.Primary,
          size = SbControlSize.Small,
          enabled = ready || sendNow,
          shortcut = "↵",
        )
      }
    }
  }
}

@Composable
internal fun CursorMcpPicker(
  value: CursorMcpDependency,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onChoose: (CursorMcpDependency) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val label = when (value) {
    CursorMcpDependency.Unspecified -> "MCP scope: choose"
    CursorMcpDependency.ProjectUserOnly -> "MCP scope: project/user only"
    CursorMcpDependency.TeamDashboardRequired -> "MCP scope: team dashboard"
  }
  Box(modifier) {
    SbButton(label, { open = true }, size = SbControlSize.Small, enabled = enabled)
    if (open) {
      ShellLayer { open = false }
      SbPopover(expanded = true, onDismissRequest = { open = false }, placement = SbPopoverPlacement.Above) {
        SbMenu(
          listOf(
            SbMenuEntry.Item(
              "Choose later",
              {
                open = false
                onChoose(CursorMcpDependency.Unspecified)
              },
              checked = value == CursorMcpDependency.Unspecified,
            ),
            SbMenuEntry.Item(
              "Project/user configuration only",
              {
                open = false
                onChoose(CursorMcpDependency.ProjectUserOnly)
              },
              checked = value == CursorMcpDependency.ProjectUserOnly,
            ),
            SbMenuEntry.Item(
              "Requires team dashboard server",
              {
                open = false
                onChoose(CursorMcpDependency.TeamDashboardRequired)
              },
              checked = value == CursorMcpDependency.TeamDashboardRequired,
            ),
          ),
        )
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
