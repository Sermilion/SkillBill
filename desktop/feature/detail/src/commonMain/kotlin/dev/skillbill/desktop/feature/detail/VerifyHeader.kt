package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.detail.DocHeader
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyHeader(run: VerifyRunDetails, onAction: (DetailAction) -> Unit, runActions: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  DocHeader(
    title = run.issueKey?.let { "Verify $it" } ?: "Verify run",
    modifier = Modifier.padding(bottom = 8.dp),
    eyebrow = {
      SbLabel(verifyStatusLabel(run), SbLabelStyle.Tint(verifyStatusTone(run)))
      SbLabel(run.issueKey ?: "No issue key")
      SbLabel("Verify")
    },
    sub = {
      Column {
        FlowRow {
          run.repoRoot?.let { root ->
            val folder = root.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { root }
            SbTooltip(root) { BasicText(folder, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted)) }
          } ?: BasicText("Unknown repository", style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
          BasicText("  ·  ${run.target ?: "No target"}  ·  ${run.baseRevision?.take(7) ?: "?"}..${run.headRevision?.take(7) ?: "?"}  ·  ${verifyIntakeLabel(run.intake)}", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
        }
        run.startedAt?.let { started ->
          val elapsed = run.finishedAt?.takeIf { verifyRunState(run) == VerifyRunState.Finished }?.let { (it - started).inWholeMilliseconds.coerceAtLeast(0) }
          BasicText(
            "Started ${formatInstant(started, "d MMM HH:mm")}${elapsed?.let { "  ·  ${activityDuration(it)}" }.orEmpty()}",
            style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint),
          )
        }
      }
    },
    actions = { runActions() },
  )
  Column {
    run.supersededBy?.let { newerRun ->
      BasicText("A newer verify run replaced this one.")
      SbButton("Open newer run", { onAction(DetailAction.OpenNewerVerifyRun(newerRun)) })
    }
  }
}

@Composable
private fun verifyStatusTone(run: VerifyRunDetails) = when (verifyRunState(run)) {
  VerifyRunState.Pending -> SkillBillTheme.colors.fgFaint
  VerifyRunState.Running -> SkillBillTheme.colors.stActive
  VerifyRunState.Parked -> SkillBillTheme.colors.stBlocked
  VerifyRunState.Superseded -> SkillBillTheme.colors.fgFaint
  VerifyRunState.Stopped -> SkillBillTheme.colors.stFailed
  VerifyRunState.Finished -> SkillBillTheme.colors.stDone
}
