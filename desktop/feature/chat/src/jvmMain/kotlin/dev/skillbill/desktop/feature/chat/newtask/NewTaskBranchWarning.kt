package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.domain.model.newWorkBranchProblem
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.feature.chat.ChatMessage

@Composable
internal fun NewTaskBranchWarning(state: NewTaskUiState, draft: NewTaskDraft, enabled: Boolean, onAction: (NewTaskAction) -> Unit, requested: Boolean) {
  val loaded = (state.branchRead as? BranchReadState.Loaded)?.takeIf { it.root == draft.repository?.root }
  val branches = loaded?.branches
  val warning = branches?.newWorkBranchProblem(draft.baseBranch)?.takeIf { requested }
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (warning != null) {
      Callout(FeedbackTone.Warn, body = warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        branches.defaultBranch?.let { default ->
          SbButton("Switch to $default", { onAction(NewTaskAction.SwitchToDefaultBranch) }, enabled = enabled, size = SbControlSize.Small)
        }
        branches.currentBranch?.let {
          SbButton("Use current branch as base", { onAction(NewTaskAction.UseCurrentBranchAsBase) }, enabled = enabled, size = SbControlSize.Small)
        }
      }
    }
    state.repositoryError?.let { Callout(FeedbackTone.Error, body = it) }
  }
}

internal fun newTaskNeedsBranchWarning(transcript: List<ChatMessage>): Boolean =
  when (val latest = transcript.lastOrNull { it is ChatMessage.Operator || it is ChatMessage.Agent }) {
    is ChatMessage.Agent -> latest.actions.any { it.start != null || it.mode == ChatPermissionMode.Act }
    is ChatMessage.Operator -> latest.action?.let { it.start != null || it.mode == ChatPermissionMode.Act } == true
    else -> false
  }
