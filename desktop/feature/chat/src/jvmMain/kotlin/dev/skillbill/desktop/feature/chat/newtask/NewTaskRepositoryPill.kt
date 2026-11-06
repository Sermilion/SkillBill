package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

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

internal fun repositoryPillBranch(baseBranch: String, root: String?, read: BranchReadState): String = baseBranch.ifBlank {
  (read as? BranchReadState.Loaded)?.takeIf { it.root == root }?.branches?.defaultBranch ?: "default branch"
}

/** The repository and base branch chip; a long branch shrinks first, so the repository name and the chevron stay readable. */
@Composable
internal fun NewTaskRepositoryPill(mode: RepositoryPillMode, repositoryName: String?, branch: String, focusRequester: FocusRequester, onClick: () -> Unit) {
  val why = when (mode) {
    is RepositoryPillMode.Static -> mode.why
    RepositoryPillMode.Starting -> PICKERS_DISABLED_STARTING
    RepositoryPillMode.Choose, RepositoryPillMode.Interactive -> null
  }
  val interaction = when (mode) {
    is RepositoryPillMode.Static -> Modifier.semantics(mergeDescendants = true) {}
    RepositoryPillMode.Starting -> Modifier.clickable(enabled = false, onClickLabel = REPOSITORY_PILL_CLICK_LABEL, onClick = onClick)
    RepositoryPillMode.Choose, RepositoryPillMode.Interactive ->
      Modifier.focusRequester(focusRequester).pointerHoverIcon(PointerIcon.Hand).clickable(onClickLabel = REPOSITORY_PILL_CLICK_LABEL, onClick = onClick)
  }
  val pillModifier = interaction.then(if (why != null) Modifier.semantics { stateDescription = why } else Modifier)
  if (mode == RepositoryPillMode.Starting) {
    SbTooltip(PICKERS_DISABLED_STARTING) { PillContent(mode, repositoryName, branch, pillModifier) }
  } else {
    PillContent(mode, repositoryName, branch, pillModifier)
  }
}

@Composable
private fun PillContent(mode: RepositoryPillMode, repositoryName: String?, branch: String, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(11.dp)
  val choosing = mode == RepositoryPillMode.Choose
  val style = SkillBillTheme.type.monoSm.copy(color = if (choosing) colors.accentText else colors.fgMuted)
  Row(
    modifier.height(22.dp).widthIn(max = 400.dp)
      .border(1.dp, if (choosing) colors.accentRing else colors.border, shape)
      .padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.folder, SbIconSize.Xs) }
    Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
      if (repositoryName == null) {
        BasicText("Choose repository", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
      } else {
        BasicText(repositoryName, Modifier.widthIn(max = 200.dp), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(" / ", style = style, maxLines = 1)
        BasicText(branch, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (mode !is RepositoryPillMode.Static) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) {
        SbIcon(LucideIcons.chevronDown, SbIconSize.Xs, contentDescription = REPOSITORY_PILL_CHEVRON)
      }
    }
  }
}
