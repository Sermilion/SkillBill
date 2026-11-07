package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.IdTag
import dev.skillbill.desktop.core.designsystem.component.ReasonBlock
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.detail.DocHeader
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.MainCheckoutOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState

internal data class QaHeaderFacts(val testedCommit: String?, val criteriaSource: String?)

/**
 * The document header (HANDOFF 9.2 items 1 to 4): chips, title, repository location, then [actions] (the run actions), all in
 * one `.sb-doc-hd` 10 apart. It reads only the row it is given, so it paints in the frame the route opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailHeader(
  item: WorkItem,
  sectionsStale: Boolean,
  onRevealFolder: () -> Unit,
  onOpenBranch: () -> Unit,
  qa: QaHeaderFacts? = null,
  validation: Boolean = false,
  planReady: Boolean = false,
  planBlocked: Boolean = false,
  actions: (@Composable () -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  DocHeader(
    title = when {
      validation -> item.branch?.let { "Validation on $it" } ?: item.title
      qa != null -> item.branch?.let { "QA on branch $it" } ?: item.title
      else -> item.title
    },
    eyebrow = {
      SbLabel(
        when {
          planBlocked -> "Blocked"
          planReady -> "Plan ready"
          item.isStandalonePlan() && item.running -> "Planning"
          else -> lifecycleLabel(item.lifecycle)
        },
        SbLabelStyle.Tint(if (planBlocked) colors.stBlocked else lifecycleColor(item.lifecycle)),
      )
      if (item.stalledSince != null) SbLabel("Stalled", SbLabelStyle.Tint(colors.stBlocked))
      IdTag(rowKey(item))
      if (item.kind != WorkKind.Goal && qa == null) item.issueKey?.let { IdTag(it) }
      SbLabel(kindWord(item.kind), SbLabelStyle.Plain)
      if (item.isStandalonePlan()) {
        val execution = listOfNotNull(item.model ?: item.agentId, item.effort?.let { "$it effort" }).joinToString(" · ")
        if (execution.isNotBlank()) BasicText(execution, style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
      }
      // Freshness only means something while the run can still change; a completed item drops the pill.
      if (item.lifecycle != Lifecycle.Terminal) {
        val fresh = item.fresh && !sectionsStale
        SbLabel(if (fresh) "fresh" else "stale", SbLabelStyle.Dot(if (fresh) colors.stDone else colors.fgFaint))
      }
    },
    // `.l-row g-1 l-wrap`: the folder and branch buttons, 4 apart, wrapping.
    sub = {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        if (item.repositoryRoot.isNotBlank()) {
          SbButton(abbreviateHome(item.repositoryRoot), onRevealFolder, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.folder)
        }
        val branch = item.branch
        if (branch != null) {
          val label = branch + (qa?.testedCommit?.let { " · ${it.take(7)}" } ?: "")
          SbButton(label, onOpenBranch, variant = SbButtonVariant.Ghost, size = SbControlSize.Small, icon = LucideIcons.branch)
        } else {
          BasicText("no branch", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
        }
        if (qa != null) (qa.criteriaSource ?: item.issueKey)?.let { source ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SbIcon(LucideIcons.checkCircle, SbIconSize.Xs)
            BasicText("Criteria from $source", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
          }
        }
        if (validation) item.agentId?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted)) }
      }
      when (val worktree = item.goalWorktree) {
        null -> Unit
        else -> when (worktree.state) {
          GoalWorktreeState.Removed -> {
            val detail = worktree.mainCheckoutDetail?.let { ": $it" }.orEmpty()
            val outcome = when (worktree.mainCheckout) {
              MainCheckoutOutcome.CheckedOut -> "The main checkout is now on its branch."
              MainCheckoutOutcome.SkippedUncommittedChanges -> "The main checkout was not switched to its branch because it has uncommitted changes$detail."
              MainCheckoutOutcome.Failed -> "The main checkout could not be switched to its branch$detail."
              null -> "Its branch stays."
            }
            BasicText("The goal's worktree was removed. $outcome", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
          }
          GoalWorktreeState.Retained ->
            BasicText(
              "The goal's worktree stays at ${worktree.path}: ${worktree.retentionDetail ?: worktree.retentionReason.orEmpty()}.",
              style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted),
            )
          GoalWorktreeState.Active -> Unit
        }
      }
      item.launchWarning?.let { warning ->
        BasicText(warning, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
      }
    },
    actions = actions?.let { { it() } },
  )
}

/** HANDOFF 9.2 item 6; the left rule takes the lifecycle color, as the chip does. */
@Composable
internal fun DetailReason(item: WorkItem, onCopy: (String) -> Unit) {
  val reason = item.reason ?: return
  ReasonBlock(label = "Run reason", text = reason, onCopy = onCopy, color = lifecycleColor(item.lifecycle))
}
