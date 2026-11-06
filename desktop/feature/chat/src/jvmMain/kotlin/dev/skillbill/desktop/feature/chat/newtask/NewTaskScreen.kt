package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.feature.chat.ChatTranscript
import kotlin.time.Instant

private val CONTENT_MAX_WIDTH = 720.dp

private const val DIMMED_ALPHA = .45f

/** What the screen asks of its host. A null [onStart] keeps the chips disabled until a start path exists. */
internal class NewTaskScreenHost(
  val now: () -> Instant,
  val onOpenRun: (WorkItem) -> Unit,
  val onShowAllWork: () -> Unit,
  val onStart: ((String, String?, ChatSuggestedAction?) -> Unit)? = null,
)

@Composable
internal fun NewTaskScreen(state: NewTaskUiState, draft: NewTaskDraft, host: NewTaskScreenHost, onAction: (NewTaskAction) -> Unit) {
  val chat = draft.chat.collectAsState().value
  val offer by draft.offer.collectAsState()
  val chatBusy = chat?.thread?.busy?.collectAsState()?.value ?: false
  val focusManager = LocalFocusManager.current
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { draft.refreshSaved() }
  Column(Modifier.fillMaxSize()) {
    ViewHeader(
      crumbs = listOf(Crumb("New task")),
      actions = {
        if (chat != null) {
          if (!chat.repositoryMissing) SbButton("New chat", { onAction(NewTaskAction.NewChat) }, size = SbControlSize.Small)
          SbButton("Discard", { onAction(NewTaskAction.Discard) }, size = SbControlSize.Small)
        }
      },
    )
    if (chat == null) {
      NewTaskHomeBody(state, draft, host, focus, onAction, Modifier.weight(1f))
    } else {
      key(chat) {
        ChatTranscript(
          chat.thread,
          gone = false,
          mode = ChatPermissionMode.Act,
          modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = COMPOSER_MAX_WIDTH),
          allowStartActions = host.onStart != null,
          allowSuggestedActions = !offer.starting,
          footer = if (offer.prefixKind != null) {
            {
              NewTaskPrefixPrompt(
                offer.choices,
                enabled = !chatBusy && !offer.starting && offer.started == null,
                onChoose = draft::retryPrefix,
              )
            }
          } else null,
        ) { _, action ->
          val start = action.start
          if (start == null) {
            if (draft.sendSuggestedAction(action)) {
              focusManager.clearFocus()
            }
          } else if (host.onStart != null) {
            val monitorTarget =
              action.target?.trim()?.takeIf(String::isNotBlank)
                ?: action.instruction.trim().takeIf { start == NewTaskActionKind.Monitor }
            host.onStart?.invoke(start.wire, monitorTarget, action)
          }
        }
      }
    }
    Column(
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (chat == null && state.home is NewTaskHome.Returning) {
        val startable = draft.repository != null && draft.agentId != null
        NewTaskChips(
          host.onStart?.takeIf { startable }?.let { onStart -> { wire, target -> onStart(wire, target, null) } },
          Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth(),
        )
      }
      NewTaskComposer(state, draft, focus, host.now, onAction)
    }
  }
}

@Composable
private fun NewTaskHomeBody(
  state: NewTaskUiState,
  draft: NewTaskDraft,
  host: NewTaskScreenHost,
  focus: FocusRequester,
  onAction: (NewTaskAction) -> Unit,
  modifier: Modifier,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reducedMotion = LocalReducedMotion.current
  val preview = previewRoute(draft.text)
  val progress = if (!reducedMotion && preview == null) rememberIdleProgress() else null
  val homeAlpha by animateFloatAsState(if (preview != null) DIMMED_ALPHA else 1f, hoverSpec())
  val saved by draft.saved.collectAsState()
  BoxWithConstraints(modifier.fillMaxWidth()) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      val returning = state.home as? NewTaskHome.Returning
      val hasInbox = returning != null || saved.isNotEmpty()
      Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (hasInbox) 32.dp else 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          BasicText("What should we ship?", Modifier.semantics { heading() }, style = type.t2xl.copy(color = colors.fg))
          BasicText(
            "Describe the work or paste an issue. Skill Bill plans it, builds it, audits it and opens the PR.",
            style = type.tBase.copy(color = colors.fgMuted),
          )
        }
        StepLine(progress, reducedMotion, preview)
        val shown = if (returning != null) saved else saved.take(NEW_TASK_HISTORY_ROWS)
        if (hasInbox) {
          InboxSection(
            needsYou = returning?.needsYou.orEmpty(),
            running = returning?.runningItems.orEmpty(),
            chats = shown,
            savedCount = saved.size,
            selectedRoot = draft.repository?.root,
            home = returning,
            showAll = saved.size > shown.size,
            now = host.now,
            onOpenRun = host.onOpenRun,
            onResume = { onAction(NewTaskAction.ResumeDraft(it)) },
            onDiscard = { onAction(NewTaskAction.DiscardDraft(it)) },
            onShowAll = { onAction(NewTaskAction.ShowAllDrafts) },
            onShowAllWork = host.onShowAllWork,
            modifier = Modifier.alpha(homeAlpha),
          )
        }
        if (state.home == NewTaskHome.FirstRun) {
          ExamplesSection(
            readinessLine(state.detectedAgents, draft.repository?.name),
            onExample = {
              onAction(NewTaskAction.TextChanged(it))
              focus.requestFocus()
            },
            modifier = Modifier.alpha(homeAlpha),
          )
        }
      }
    }
  }
}
