package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbPickerButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

internal const val REPOSITORY_PILL_CHEVRON = "Open repository picker"
private const val REPOSITORY_PILL_CLICK_LABEL = "Change repository or base branch"

internal sealed interface RepositoryPillMode {
  data class Static(val why: String?) : RepositoryPillMode

  data object Starting : RepositoryPillMode

  data object Choose : RepositoryPillMode

  data object Interactive : RepositoryPillMode
}

internal fun repositoryPillMode(chat: NewTaskChat?, busy: Boolean, starting: Boolean, repository: NewTaskRepository?): RepositoryPillMode = when {
  chat?.repositoryMissing == true -> RepositoryPillMode.Static(PICKERS_DISABLED_MISSING_REPO)
  starting -> RepositoryPillMode.Starting
  busy -> RepositoryPillMode.Static(PICKERS_DISABLED_BUSY)
  repository == null -> RepositoryPillMode.Choose
  else -> RepositoryPillMode.Interactive
}

/** The chosen base branch, or the live checkout branch while no base is chosen. */
internal fun repositoryPillBranch(baseBranch: String, root: String?, read: BranchReadState): String = baseBranch.ifBlank {
  when (read) {
    is BranchReadState.Loaded -> if (read.root == root) read.branches.currentBranch ?: "detached HEAD" else "Reading branch"
    is BranchReadState.Failed -> "Branch unavailable"
    BranchReadState.Idle, is BranchReadState.Reading -> "Reading branch"
  }
}

/** The repository and branch chip; a long branch shrinks first, so the repository name and the chevron stay readable. */
@Composable
internal fun NewTaskRepositoryPill(mode: RepositoryPillMode, repositoryName: String?, branch: String, focusRequester: FocusRequester, onClick: () -> Unit) {
  val why = when (mode) {
    is RepositoryPillMode.Static -> mode.why
    RepositoryPillMode.Starting -> PICKERS_DISABLED_STARTING
    RepositoryPillMode.Choose, RepositoryPillMode.Interactive -> null
  }
  val modeModifier = when (mode) {
    is RepositoryPillMode.Static -> Modifier.semantics(mergeDescendants = true) {}
    RepositoryPillMode.Starting -> Modifier
    RepositoryPillMode.Choose, RepositoryPillMode.Interactive -> Modifier.focusRequester(focusRequester)
  }
  val pillModifier = modeModifier.widthIn(max = 400.dp).then(if (why != null) Modifier.semantics { stateDescription = why } else Modifier)
  if (mode == RepositoryPillMode.Starting) {
    SbTooltip(PICKERS_DISABLED_STARTING) { PillContent(mode, repositoryName, branch, pillModifier, onClick) }
  } else {
    PillContent(mode, repositoryName, branch, pillModifier, onClick)
  }
}

@Composable
private fun PillContent(mode: RepositoryPillMode, repositoryName: String?, branch: String, modifier: Modifier, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val choosing = mode == RepositoryPillMode.Choose
  val style = SkillBillTheme.type.monoSm.copy(color = if (choosing) colors.accentText else colors.fgMuted)
  SbPickerButton(
    onClick = if (mode is RepositoryPillMode.Static) null else onClick,
    modifier = modifier,
    icon = LucideIcons.folder,
    open = choosing,
    enabled = mode != RepositoryPillMode.Starting,
    onClickLabel = REPOSITORY_PILL_CLICK_LABEL,
    chevronDescription = REPOSITORY_PILL_CHEVRON,
  ) {
    Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
      if (repositoryName == null) {
        BasicText("Choose repository", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } else {
        BasicText(repositoryName, Modifier.widthIn(max = 200.dp), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(" / ", style = style, maxLines = 1)
        BasicText(branch, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}
