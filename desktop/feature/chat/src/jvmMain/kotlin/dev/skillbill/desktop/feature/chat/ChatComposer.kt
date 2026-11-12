package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatImage
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.CursorMcpDependency
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.feature.chat.newtask.IssuePrefixPicker
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The active chat's input. Enter sends and Shift+Enter starts a new line; "Pause and act" shows only while the execution runs.
 * Stop ends the running reply and returns ordinary queued messages to the draft. Send now follows the provider delivery policy.
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
  val coordinationMode by session.coordinationMode.collectAsState()
  val id = session.id
  val cursorMcpDependency = workspace.cursorMcpDependencies[id] ?: CursorMcpDependency.ProjectUserOnly
  val choices by session.resumeChoices.collectAsState()
  val queued by session.queued.collectAsState()
  val delivery by session.thread.delivery.collectAsState()
  val prefixChoice by session.prefixChoice.collectAsState()
  val focus = remember(session) { FocusRequester() }
  val gate = remember(session) { SendNowGate() }
  LaunchedEffect(session) { focus.requestFocus() }
  val text = workspace.drafts[id].orEmpty()
  val command = text.trim().takeIf { it in CHAT_COMMANDS }
  val images = workspace.pastedImages[id].orEmpty()
  val ready = command != null || (supported && !gone && (text.isNotBlank() || images.isNotEmpty()))
  val sendNow = delivery == null && stoppable && queued.isNotEmpty() && text.isBlank() && images.isEmpty() && supported && !gone
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
  Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    ChatActivityPill(session.thread, gone)
    delivery?.let {
      ChatDeliveryRecovery(it, { key -> workspace.retryDelivery(id, key) },
        { key -> workspace.discardDelivery(id, key) }, session.thread::keepOpen)
    }
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
            workspace.cursorMcpDependencies[id] = CursorMcpDependency.ProjectUserOnly
          }
          workspace.drafts[id] = it
        },
        modifier = Modifier.fillMaxWidth()
          .pasteChatImages(supported && !gone, clipboard, encodeContext) { workspace.addPastedImage(id, it) }
          .onPreviewKeyEvent { event ->
            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) gate.enterReleased()
            false
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
          saved, workspace.now(), ResumeScope(session.item.branch ?: File(session.item.workingRoot()).name, worktree = true),
          { session.item.branch }, focus, { workspace.resume(id, it) }, { workspace.dismissResume(id) },
        )
      }
    }
    if (images.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // The remove control leaves with its chip, so focus returns to the input for the next paste.
        images.forEachIndexed { index, image ->
          ImageChip(index + 1, image, onRemove = {
            workspace.removePastedImage(id, index)
            focus.requestFocus()
          })
        }
      }
    }
    if (queued.isNotEmpty()) QueuedMessagesPreview(queued)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      ModeBadge(ChatPermissionMode.Act)
      AgentPicker(
        agent, workspace.agents, session.item.repositoryRoot, enabled = delivery == null && !busy && !gone,
        loadModels = workspace::models, onChooseModel = { workspace.switchModel(id, it) },
      ) { workspace.switchAgent(id, it) }
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
        if (coordinationMode == ChatPermissionMode.ReadOnly) {
          SbButton(
            if (busy || delivery != null) "Queue pause and act" else "Pause and act",
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
            busy || delivery != null -> "Queue"
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
private fun QueuedMessagesPreview(queued: List<QueuedChatMessage>) {
  val anchorSource = remember { MutableInteractionSource() }
  val anchorHovered by anchorSource.collectIsHoveredAsState()
  val anchorFocused by anchorSource.collectIsFocusedAsState()
  val popupSource = remember { MutableInteractionSource() }
  val popupHovered by popupSource.collectIsHoveredAsState()
  val pillFocus = remember { FocusRequester() }
  val scrollFocus = remember { FocusRequester() }
  val scrollState = rememberScrollState()
  val scrollScope = rememberCoroutineScope()
  val focusManager = LocalFocusManager.current
  var keyboardOpen by remember { mutableStateOf(false) }
  var pointerVisited by remember { mutableStateOf(false) }
  var suppressHover by remember { mutableStateOf(false) }
  var returnFocusToPill by remember { mutableStateOf(false) }
  var focusDirection by remember { mutableStateOf<FocusDirection?>(null) }
  val dismiss = {
    returnFocusToPill = returnFocusToPill || keyboardOpen
    keyboardOpen = false
    suppressHover = pointerVisited || anchorHovered || popupHovered
  }
  LaunchedEffect(anchorHovered, popupHovered) {
    if (anchorHovered || popupHovered) {
      pointerVisited = true
    } else if (pointerVisited) {
      delay(150)
      if (!anchorHovered && !popupHovered) {
        pointerVisited = false
        suppressHover = false
      }
    }
  }
  LaunchedEffect(keyboardOpen, queued.isNotEmpty()) {
    if (!keyboardOpen && returnFocusToPill && queued.isNotEmpty()) {
      pillFocus.requestFocus()
      focusDirection?.let { focusManager.moveFocus(it) }
      focusDirection = null
      returnFocusToPill = false
    }
    if (queued.isEmpty()) {
      keyboardOpen = false
      returnFocusToPill = false
    }
  }
  val open = queued.isNotEmpty() && (keyboardOpen || (pointerVisited && !suppressHover))
  val density = LocalDensity.current
  val window = LocalWindowInfo.current.containerSize
  val windowWidth = with(density) { window.width.toDp() }
  val windowHeight = with(density) { window.height.toDp() }
  val maxWidth = (windowWidth - 24.dp).coerceAtLeast(0.dp).coerceAtMost(SkillBillSize.popoverMax)
  val maxHeight = (windowHeight - 24.dp).coerceAtLeast(0.dp)
  val positionProvider = remember(density) {
    QueuePreviewPositionProvider(with(density) { 8.dp.roundToPx() })
  }
  val countLabel = "${queued.size} ${if (queued.size == 1) "message" else "messages"} queued"
  Box(
    Modifier.focusRequester(pillFocus)
      .background(if (anchorFocused) SkillBillTheme.colors.bgHover else Color.Transparent)
      .hoverable(anchorSource)
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Escape -> { dismiss(); true }
          Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            suppressHover = false
            keyboardOpen = true
            true
          }
          else -> false
        }
      }
      .clickable(interactionSource = anchorSource, indication = null) {
        if (keyboardOpen) dismiss() else {
          suppressHover = false
          keyboardOpen = true
        }
      }
      .pointerHoverIcon(PointerIcon.Hand)
      .semantics {
        role = Role.Button
        contentDescription = countLabel
        stateDescription = if (open) "Preview open" else "Preview closed"
      },
  ) {
    SbLabel(countLabel)
    if (open) {
      Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = {
          keyboardOpen = false
          suppressHover = pointerVisited || anchorHovered || popupHovered
        },
        properties = PopupProperties(focusable = keyboardOpen, dismissOnBackPress = keyboardOpen, dismissOnClickOutside = keyboardOpen),
      ) {
        val shape = RoundedCornerShape(SkillBillRadius.sm)
        LaunchedEffect(keyboardOpen) {
          if (keyboardOpen) scrollFocus.requestFocus()
        }
        Column(
          Modifier.hoverable(popupSource)
            .widthIn(max = maxWidth)
            .heightIn(max = maxHeight)
            .layeredShadow(SkillBillTheme.shadows.popover, shape)
            .background(SkillBillTheme.colors.bgOverlay, shape)
            .border(1.dp, SkillBillTheme.colors.borderStrong, shape)
            .onPreviewKeyEvent { event ->
              if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return@onPreviewKeyEvent false
              when (event.key) {
                Key.Escape -> { dismiss(); true }
                Key.Tab -> {
                  focusDirection = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                  dismiss()
                  true
                }
                Key.DirectionUp -> { scrollScope.launch { scrollState.scrollTo((scrollState.value - 40).coerceAtLeast(0)) }; true }
                Key.DirectionDown -> { scrollScope.launch { scrollState.scrollTo((scrollState.value + 40).coerceAtMost(scrollState.maxValue)) }; true }
                Key.PageUp -> { scrollScope.launch { scrollState.scrollTo((scrollState.value - scrollState.viewportSize).coerceAtLeast(0)) }; true }
                Key.PageDown -> { scrollScope.launch { scrollState.scrollTo((scrollState.value + scrollState.viewportSize).coerceAtMost(scrollState.maxValue)) }; true }
                Key.MoveHome -> { scrollScope.launch { scrollState.scrollTo(0) }; true }
                Key.MoveEnd -> { scrollScope.launch { scrollState.scrollTo(scrollState.maxValue) }; true }
                else -> false
              }
            }
            .verticalScroll(scrollState)
            .focusRequester(scrollFocus)
            .focusable(enabled = keyboardOpen)
            .padding(10.dp)
            .semantics { paneTitle = "Queued messages" },
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          BasicText("Queued messages", style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
          queued.forEachIndexed { index, message ->
            QueuePreviewEntry(index + 1, message)
          }
        }
      }
    }
  }
}

@Composable
private fun QueuePreviewEntry(number: Int, message: QueuedChatMessage) {
  val colors = SkillBillTheme.colors
  Column(
    Modifier.fillMaxWidth()
      .border(1.dp, colors.borderSubtle, RoundedCornerShape(SkillBillRadius.xs))
      .padding(horizontal = 9.dp, vertical = 7.dp)
      .semantics {
        contentDescription = if (message.text.isBlank() && message.images.isNotEmpty()) {
          "Queued message $number, image attachment"
        } else {
          "Queued message $number, ${message.text}"
        }
      },
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    BasicText("Message $number", style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
    if (message.text.isNotBlank()) {
      BasicText(
        message.text,
        style = SkillBillTheme.type.tXs.copy(color = colors.fg2, lineHeight = 1.35.em),
        softWrap = true,
        overflow = TextOverflow.Clip,
      )
    }
    if (message.images.isNotEmpty() && message.text.isBlank()) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SbIcon(LucideIcons.image, SbIconSize.Xs)
        BasicText("Image attachment", style = SkillBillTheme.type.tXs.copy(color = colors.fg2))
      }
    }
  }
}

private class QueuePreviewPositionProvider(private val margin: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val rightAligned = layoutDirection == LayoutDirection.Ltr
    val preferredX = if (rightAligned) anchorBounds.left else anchorBounds.right - popupContentSize.width
    val x = preferredX.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
    val below = anchorBounds.bottom
    val y = if (below + popupContentSize.height <= windowSize.height - margin) {
      below
    } else {
      (anchorBounds.top - popupContentSize.height).coerceAtLeast(margin)
    }
    return IntOffset(x, y)
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
