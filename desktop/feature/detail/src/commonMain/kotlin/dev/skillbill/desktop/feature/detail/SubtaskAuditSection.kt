package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults

@Composable
internal fun SubtaskAuditSection(results: SubtaskPhaseResults?, kind: StatusKind, subtaskId: Int) {
  val audit = results?.auditResult
  val reviewStarted = results?.runningReviewPass != null || !results?.reviewPasses.isNullOrEmpty()
  val upcoming = !reviewStarted && (kind == StatusKind.Pending || kind == StatusKind.Active || kind == StatusKind.Blocked)
  if (audit == null && !upcoming) return

  val attemptTotal = audit?.attemptTotal
  val end = when {
    audit?.outcome == AuditOutcome.Running -> "attempt ${audit.attempts.size + 1} running"
    attemptTotal != null && attemptTotal > 0 -> if (attemptTotal == 1) "1 attempt" else "$attemptTotal attempts"
    else -> null
  }
  PaneSection(
    title = "Audit",
    end = end?.let { label ->
      { BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted)) }
    },
  ) {
    if (audit == null) {
      BasicText(
        "Runs after implementation",
        style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fgMuted),
      )
    } else {
      AuditResult(audit, results.audit?.verdict, results.audit?.summary ?: results.audit?.detail, subtaskId)
    }
  }
}

@Composable
private fun AuditResult(audit: SubtaskAuditResult, verdict: String?, summary: String?, subtaskId: Int) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (audit.outcome) {
      AuditOutcome.Passed -> VerdictLine("Passed", audit.countsText(), SkillBillTheme.colors.stDone)
      AuditOutcome.Failed -> VerdictLine("Failed", audit.countsText(), SkillBillTheme.colors.stFailed)
      AuditOutcome.Running -> VerdictLine(
        "Attempt ${audit.attempts.size + 1}",
        "Auditing",
        SkillBillTheme.colors.stActive,
      )
      AuditOutcome.Unknown -> VerdictLine(
        resultsVerdict(verdict),
        null,
        SkillBillTheme.colors.fgFaint,
      )
    }
    if (audit.outcome != AuditOutcome.Running) {
      summary?.takeIf { it.isNotBlank() }?.let {
        ExpandableText(it, SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.45.em))
      }
    }
    when (audit.outcome) {
      AuditOutcome.Passed ->
        if (audit.criteria.isNotEmpty()) {
          CriterionBox {
            audit.criteria.forEachIndexed { index, criterion ->
              CriterionRow(
                criterion,
                Modifier.fillMaxWidth().sbBoxSegment(index, audit.criteria.size, fill = SkillBillTheme.colors.bgInset),
              )
            }
          }
        }
      AuditOutcome.Failed -> FailedCriteria(audit, subtaskId)
      AuditOutcome.Running, AuditOutcome.Unknown -> Unit
    }
    val earlierAttempts = audit.attempts.dropLast(if (audit.outcome == AuditOutcome.Running) 0 else 1)
    earlierAttempts.forEach { AuditAttemptLine(it) }
  }
}

@Composable
private fun FailedCriteria(audit: SubtaskAuditResult, subtaskId: Int) {
  val unmet = audit.criteria.filterNot { it.met }
  val met = audit.criteria.filter { it.met }
  var expanded by rememberSaveable(subtaskId) { mutableStateOf(false) }
  if (unmet.isEmpty() && met.isEmpty()) return
  CriterionBox {
    unmet.forEachIndexed { index, criterion ->
      UnmetCriterionRow(
        criterion,
        Modifier.fillMaxWidth().sbBoxSegment(
          index,
          unmet.size + if (met.isNotEmpty()) 1 else 0,
          fill = SkillBillTheme.colors.bgInset,
        ),
      )
    }
    if (met.isNotEmpty()) {
      Row(
        Modifier.fillMaxWidth().sbBoxSegment(
          unmet.size,
          unmet.size + 1,
          fill = SkillBillTheme.colors.bgInset,
        ).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusDot(SkillBillTheme.colors.stDone)
        BasicText(
          "${met.size} ${if (met.size == 1) "criterion" else "criteria"} met",
          Modifier.weight(1f),
          style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted),
        )
        SbButton(if (expanded) "Hide" else "Show", { expanded = !expanded }, variant = SbButtonVariant.Ghost)
      }
      if (expanded) {
        met.forEachIndexed { index, criterion ->
          CriterionRow(
            criterion,
            Modifier.fillMaxWidth().sbBoxSegment(
              unmet.size + 1 + index,
              unmet.size + 1 + met.size,
              fill = SkillBillTheme.colors.bgInset,
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun CriterionBox(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun CriterionRow(criterion: AuditCriterion, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  Row(
    modifier.padding(horizontal = 12.dp, vertical = 9.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(if (criterion.met) colors.stDone else colors.stFailed, Modifier.padding(top = 5.dp))
    BasicText(criterion.ref, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted), maxLines = 1)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      SbTooltip(criterion.text) {
        BasicText(
          criterion.text,
          style = SkillBillTheme.type.tSm.copy(color = colors.fg2),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      criterion.fixedInAttempt?.let {
        BasicText("fixed in attempt $it", style = SkillBillTheme.type.tXs.copy(color = colors.stPlanning))
      }
    }
  }
}

@Composable
private fun UnmetCriterionRow(criterion: AuditCriterion, modifier: Modifier) {
  val colors = SkillBillTheme.colors
  Row(
    modifier.padding(horizontal = 12.dp, vertical = 9.dp),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(colors.stFailed, Modifier.padding(top = 5.dp))
    BasicText(criterion.ref, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted), maxLines = 1)
    Box(Modifier.weight(1f)) {
      SbTooltip(criterion.text) {
        BasicText(
          criterion.text,
          style = SkillBillTheme.type.tSm.copy(color = colors.fg2),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    BasicText("not met", style = SkillBillTheme.type.tXs.copy(color = colors.stFailed), maxLines = 1)
  }
}

@Composable
private fun VerdictLine(label: String, detail: String?, color: androidx.compose.ui.graphics.Color) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(color)
    BasicText(
      listOfNotNull(label, detail).joinToString(" · "),
      style = SkillBillTheme.type.tSmMedium.copy(color = color),
    )
  }
}

@Composable
private fun AuditAttemptLine(attempt: AuditAttempt) {
  val status = if (attempt.unavailableReason != null) {
    "Historical result unavailable"
  } else if (attempt.passed == true) {
    "Passed"
  } else {
    val count = requireNotNull(attempt.uncoveredCount)
    "Gaps found · $count ${if (count == 1) "criterion" else "criteria"} uncovered"
  }
  val next = attempt.nextPhaseId?.let { " → $it" }.orEmpty()
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(
      when {
        attempt.passed == true -> SkillBillTheme.colors.stDone
        attempt.passed == false -> SkillBillTheme.colors.stFailed
        else -> SkillBillTheme.colors.fgMuted
      },
    )
    BasicText(
      "Attempt ${attempt.number} · $status$next",
      style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted),
    )
  }
}

private fun SubtaskAuditResult.countsText(): String? =
  if (metCount != null && totalCount != null) "$metCount of $totalCount criteria met" else null

private fun resultsVerdict(verdict: String?): String =
  verdict?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Unknown"
