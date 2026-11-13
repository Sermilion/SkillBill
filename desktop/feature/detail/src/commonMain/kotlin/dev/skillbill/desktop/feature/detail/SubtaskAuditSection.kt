package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.ExpandableResultRow
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.ResultRowBadge
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.status.StatusDot
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults

@Composable
internal fun SubtaskAuditSection(
  results: SubtaskPhaseResults?,
  kind: StatusKind,
  subtaskId: Int,
  currentAttemptNumber: Int? = null,
) {
  val audit = results?.auditResult
  val reviewStarted = results?.runningReviewPass != null || !results?.reviewPasses.isNullOrEmpty()
  val upcoming = !reviewStarted && (kind == StatusKind.Pending || kind == StatusKind.Active || kind == StatusKind.Blocked)
  if (audit == null && !upcoming) return

  val attemptTotal = audit?.attemptTotal
  val end = when {
    audit?.outcome == AuditOutcome.Running -> currentAttemptNumber?.let { "attempt $it running" } ?: "auditing"
    attemptTotal != null && attemptTotal > 0 -> if (attemptTotal == 1) "1 attempt" else "$attemptTotal attempts"
    else -> null
  }
  Column(Modifier.fillMaxWidth().hairline(Edge.Top, SkillBillTheme.colors.borderSubtle).padding(10.dp)) {
    if (audit == null) {
      BasicText(
        "Runs after implementation",
        style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fgMuted),
      )
    } else {
      AuditResult(audit, results.audit?.verdict, results.audit?.summary ?: results.audit?.detail, subtaskId, currentAttemptNumber, end)
    }
  }
}

@Composable
private fun AuditResult(
  audit: SubtaskAuditResult,
  verdict: String?,
  summary: String?,
  subtaskId: Int,
  currentAttemptNumber: Int?,
  attemptLabel: String?,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (audit.attempts.any { it.unavailableReason != null }) {
      Hint("Some historical audit results are unavailable because their discovery scope was not retained.", FeedbackTone.Warn)
    }
    when (audit.outcome) {
      AuditOutcome.Passed -> VerdictLine("Passed", audit.countsText(), SkillBillTheme.colors.stDone, attemptLabel)
      AuditOutcome.Failed -> VerdictLine("Failed", audit.countsText(), SkillBillTheme.colors.stFailed, attemptLabel)
      AuditOutcome.Running -> VerdictLine("Auditing", audit.countsText(), SkillBillTheme.colors.stActive, attemptLabel)
      AuditOutcome.Unknown -> VerdictLine(resultsVerdict(verdict), null, SkillBillTheme.colors.fgFaint, attemptLabel)
    }
    if (audit.outcome != AuditOutcome.Running) {
      summary?.takeIf { it.isNotBlank() }?.let {
        ExpandableText(it, SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.45.em))
      }
    }
    if (audit.attempts.isNotEmpty()) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        audit.attempts.forEach { AuditAttemptLine(it) }
      }
    }
    when (audit.outcome) {
      AuditOutcome.Passed, AuditOutcome.Running ->
        if (audit.criteria.isNotEmpty()) CurrentCriteriaBox(audit.criteria, subtaskId)
      AuditOutcome.Failed -> FailedCriteria(audit, subtaskId)
      AuditOutcome.Unknown -> Unit
    }
    if (audit.outcome == AuditOutcome.Running) {
      RunningAttemptLine(currentAttemptNumber)
    }
  }
}

@Composable
private fun RunningAttemptLine(currentAttemptNumber: Int?) {
  val label = currentAttemptNumber?.let { "Attempt $it · Auditing" } ?: "Auditing"
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(SkillBillTheme.colors.stActive)
    BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
  }
}

@Composable
private fun CurrentCriteriaBox(criteria: List<AuditCriterion>, subtaskId: Int) {
  val ordered = criteria.filterNot { it.met } + criteria.filter { it.met }
  Column(Modifier.fillMaxWidth()) {
    ordered.forEachIndexed { index, criterion ->
      key(subtaskId, criterion.ref) {
        AuditCriterionRow(
          criterion,
          Modifier.fillMaxWidth().sbBoxSegment(index, ordered.size, fill = SkillBillTheme.colors.bgInset),
          subtaskId,
        )
      }
    }
  }
}

@Composable
private fun FailedCriteria(audit: SubtaskAuditResult, subtaskId: Int) {
  val unmet = audit.criteria.filterNot { it.met }
  val met = audit.criteria.filter { it.met }
  if (unmet.isEmpty() && met.isEmpty()) return
  key(subtaskId) {
    var expanded by rememberSaveable(subtaskId) { mutableStateOf(false) }
    val totalRows = unmet.size + if (met.isNotEmpty()) 1 + if (expanded) met.size else 0 else 0
    Column(Modifier.fillMaxWidth()) {
      unmet.forEachIndexed { index, criterion ->
        key(subtaskId, criterion.ref) {
          AuditCriterionRow(
            criterion,
            Modifier.fillMaxWidth().sbBoxSegment(index, totalRows, fill = SkillBillTheme.colors.bgInset),
            subtaskId,
          )
        }
      }
      if (met.isNotEmpty()) {
        val headerIndex = unmet.size
        Row(
          Modifier.fillMaxWidth().sbBoxSegment(headerIndex, totalRows, fill = SkillBillTheme.colors.bgInset)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            key(subtaskId, criterion.ref) {
              AuditCriterionRow(
                criterion,
                Modifier.fillMaxWidth().sbBoxSegment(
                  headerIndex + 1 + index,
                  totalRows,
                  fill = SkillBillTheme.colors.bgInset,
                ),
                subtaskId,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AuditCriterionRow(criterion: AuditCriterion, modifier: Modifier, subtaskId: Int) {
  var open by rememberSaveable(subtaskId, criterion.ref) { mutableStateOf(false) }
  val colors = SkillBillTheme.colors
  val badgeLabel = if (criterion.met) "Met" else "Missing"
  val badgeColor = if (criterion.met) colors.stDone else colors.stFailed
  val onToggle = remember(subtaskId, criterion.ref) { { open = !open } }
  ExpandableResultRow(
    modifier = modifier,
    open = open,
    onToggle = onToggle,
    toggleLabel = "Toggle ${criterion.ref}",
    ref = {
      Box(Modifier.width(52.dp)) {
        BasicText(criterion.ref, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted), maxLines = 1)
      }
    },
    title = {
      val titleStyle = SkillBillTheme.type.tSm.copy(color = colors.fg2, lineHeight = 1.45.em)
      BasicText(
        criterion.text,
        style = titleStyle,
        maxLines = if (open) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    stateBadge = { ResultRowBadge(badgeLabel, badgeColor) },
    disclosureInsetStart = 72.dp,
    disclosure = if (criterion.fixedInAttempt != null) {
      {
        BasicText(
          "fixed in attempt ${criterion.fixedInAttempt}",
          style = SkillBillTheme.type.tXs.copy(color = colors.stPlanning),
        )
      }
    } else null,
  )
}

@Composable
private fun VerdictLine(label: String, detail: String?, color: Color, attemptLabel: String?) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(color)
    BasicText(
      listOfNotNull(label, detail).joinToString(" · "),
      Modifier.weight(1f),
      style = SkillBillTheme.type.tSmMedium.copy(color = color),
    )
    attemptLabel?.let {
      BasicText(it, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}

@Composable
private fun AuditAttemptLine(attempt: AuditAttempt) {
  val status = if (attempt.unavailableReason != null) {
    "Historical result unavailable"
  } else if (attempt.passed == true) {
    "Passed"
  } else if (attempt.passed == false) {
    val count = requireNotNull(attempt.uncoveredCount)
    "Gaps found · $count ${if (count == 1) "criterion" else "criteria"} uncovered"
  } else {
    return
  }
  val next = attempt.nextPhaseId?.let { " → $it" }.orEmpty()
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusDot(
      when {
        attempt.passed == true -> SkillBillTheme.colors.stDone
        attempt.passed == false -> SkillBillTheme.colors.stFailed
        else -> SkillBillTheme.colors.fgMuted
      },
      modifier = Modifier.padding(top = 4.dp),
    )
    BasicText(
      "Attempt ${attempt.number} · $status$next",
      Modifier.weight(1f),
      style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted),
    )
  }
}

private fun SubtaskAuditResult.countsText(): String? =
  if (metCount != null && totalCount != null) "$metCount of $totalCount criteria met" else null

private fun resultsVerdict(verdict: String?): String =
  verdict?.replace('_', ' ')?.replaceFirstChar(Char::uppercase) ?: "Unknown"
