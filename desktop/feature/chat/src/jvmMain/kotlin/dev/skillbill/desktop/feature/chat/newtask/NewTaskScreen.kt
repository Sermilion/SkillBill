package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader

private val CONTENT_MAX_WIDTH = 680.dp

@Composable
internal fun NewTaskScreen(state: NewTaskUiState, draft: NewTaskDraft, onAction: (NewTaskAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reducedMotion = LocalReducedMotion.current
  val progress = if (!reducedMotion && draft.text.isBlank()) rememberIdleProgress() else null
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
          StepLine(progress, reducedMotion)
        }
      }
    }
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), contentAlignment = Alignment.TopCenter) {
      NewTaskComposer(state, draft, onAction)
    }
  }
}
