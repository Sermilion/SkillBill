package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun DetailSections.validationChecksSection(
  item: WorkItem,
  detail: Detail,
  state: DetailUiState,
  onAction: (DetailAction) -> Unit,
) {
  val run = currentExecution(item, detail) ?: return
  if (!run.liveChecks) return
  val live = qaRunLive(item, run)
  val summary = validationChecksSummary(run, live)
  if (summary.rows.isEmpty() && !live) return
  section("validation-checks", "Checks", count = summary.rows.size.takeIf { it > 0 }?.toString()) {
    if (summary.rows.isEmpty()) {
      this.item(key = "validation-checks-waiting") {
        BasicText("Waiting for the agent to list its checks.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint))
      }
    } else {
      items(summary.rows.size, key = { index -> "validation-check-${run.invocationId}-${summary.rows[index].check.checkId}" }) { index ->
        ValidationCheckRowContent(
          summary.rows[index], live,
          validationCheckIsOpen(summary.rows[index], run.invocationId, live, state.validationCheckOverrides),
          onToggle = { onAction(DetailAction.ToggleValidationCheck(ValidationCheckKey(run.invocationId, summary.rows[index].check.checkId))) },
        )
      }
    }
  }
}

@Composable
private fun ValidationCheckRowContent(row: ValidationCheckRow, live: Boolean, open: Boolean, onToggle: () -> Unit) {
  val colors = SkillBillTheme.colors
  val check = row.check
  Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
    Row(
      Modifier.fillMaxWidth().then(
        if (row.expandable) Modifier.clickable(role = Role.Button, onClick = onToggle)
          .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
        else Modifier,
      ).padding(vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      CheckGlyph(row.state)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(check.name, style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
        BasicText("${check.workingDir}  ${check.command}", style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted), maxLines = 1)
        row.inlineReason?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = row.state.tone())) }
      }
      if ((check.attempts.lastOrNull()?.number ?: 0) > 1) BasicText("attempt ${check.attempts.last().number}", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      val duration = check.totalDurationMs()
      when {
        row.state == ValidationCheckState.Pending || row.state == ValidationCheckState.Skipped -> BasicText("", style = SkillBillTheme.type.tXs)
        row.state == ValidationCheckState.Running -> SbClock(duration, check.attempts.last().startedAt, live = true, showSeconds = true, style = SkillBillTheme.type.tXs)
        else -> BasicText(activityDuration(duration), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
      }
      BasicText(row.state.word, style = SkillBillTheme.type.tXs.copy(color = row.state.tone()))
      if (row.expandable) SbIcon(if (open) LucideIcons.chevronDown else LucideIcons.chevronRight, 12.dp)
    }
    if (open) check.attempts.forEach { attempt -> ValidationCheckAttemptLine(attempt, live) }
  }
}

@Composable
private fun CheckGlyph(state: ValidationCheckState) {
  when (state) {
    ValidationCheckState.Running -> SbSpinner(color = state.tone(), contentDescription = "Running")
    ValidationCheckState.Pending -> StatusIcon(StatusKind.Pending, null)
    ValidationCheckState.Passed -> StatusIcon(StatusKind.Completed, null)
    ValidationCheckState.Failed -> StatusIcon(StatusKind.Failed, null)
    ValidationCheckState.Blocked -> StatusIcon(StatusKind.Blocked, null)
    ValidationCheckState.Skipped -> StatusIcon(StatusKind.Skipped, null)
    ValidationCheckState.NotFinished -> StatusIcon(StatusKind.Canceled, null, tint = state.tone())
  }
}

@Composable
private fun ValidationCheckAttemptLine(attempt: ValidationCheckAttempt, live: Boolean) {
  val colors = SkillBillTheme.colors
  val state = attempt.result?.let { it.displayState() }
  Row(Modifier.fillMaxWidth().padding(start = 22.dp, top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    BasicText("#${attempt.number}", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
    BasicText(attempt.startedAt?.let { formatInstant(it, "d MMM HH:mm:ss") }.orEmpty(), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted))
    BasicText(state?.word ?: if (attempt.startedAt != null && live) "running" else "not finished", style = SkillBillTheme.type.tXs.copy(color = state?.tone() ?: colors.fgFaint))
    attempt.exitCode?.let { BasicText("exit $it", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
    if (attempt.startedAt != null && attempt.finishedAt == null && live) {
      SbClock(0L, attempt.startedAt, live = true, showSeconds = true, style = SkillBillTheme.type.tXs)
    } else attempt.durationMs()?.let { BasicText(activityDuration(it), style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted)) }
    attempt.note?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint)) }
  }
}
