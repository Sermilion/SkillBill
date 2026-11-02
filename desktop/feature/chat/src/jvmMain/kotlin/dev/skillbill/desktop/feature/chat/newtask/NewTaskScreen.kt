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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.hoverSpec
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

private val CONTENT_MAX_WIDTH = 680.dp

private const val DIMMED_ALPHA = .45f

/** What the screen asks of its host. A null [onStart] keeps the chips disabled until a start path exists. */
internal class NewTaskScreenHost(
  val now: () -> Instant,
  val onOpenRun: (WorkItem) -> Unit,
  val onShowAllWork: () -> Unit,
  val onStart: ((String) -> Unit)? = null,
)

@Composable
internal fun NewTaskScreen(state: NewTaskUiState, draft: NewTaskDraft, host: NewTaskScreenHost, onAction: (NewTaskAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reducedMotion = LocalReducedMotion.current
  val preview = previewRoute(draft.text)
  val progress = if (!reducedMotion && preview == null) rememberIdleProgress() else null
  val focus = remember { FocusRequester() }
  val homeAlpha by animateFloatAsState(if (preview != null) DIMMED_ALPHA else 1f, hoverSpec())
  Column(Modifier.fillMaxSize()) {
    ViewHeader(crumbs = listOf(Crumb("New task")))
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
      Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(40.dp)) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("What should we ship?", Modifier.semantics { heading() }, style = type.t2xl.copy(color = colors.fg))
            BasicText(
              "Describe the work or paste an issue. Skill Bill plans it, builds it, audits it and opens the PR.",
              style = type.tBase.copy(color = colors.fgMuted),
            )
          }
          StepLine(progress, reducedMotion, preview)
          when (val home = state.home) {
            NewTaskHome.Pending -> Unit
            is NewTaskHome.Returning -> NeedsYouSection(home, host.now, host.onOpenRun, host.onShowAllWork, Modifier.alpha(homeAlpha))
            NewTaskHome.FirstRun -> ExamplesSection(
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
    Column(
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (state.home is NewTaskHome.Returning) {
        val startable = draft.repository != null && draft.agentId != null
        NewTaskChips(host.onStart.takeIf { startable }, Modifier.widthIn(max = COMPOSER_MAX_WIDTH).fillMaxWidth())
      }
      NewTaskComposer(state, draft, focus, onAction)
    }
  }
}
