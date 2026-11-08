package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.PeekCheck
import dev.skillbill.desktop.core.designsystem.detail.PeekCheckState
import dev.skillbill.desktop.core.designsystem.detail.PeekFacts
import dev.skillbill.desktop.core.designsystem.detail.PeekFooter
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PeekSignal
import dev.skillbill.desktop.core.designsystem.detail.PeekStat
import dev.skillbill.desktop.core.designsystem.detail.PeekStatTone
import dev.skillbill.desktop.core.designsystem.detail.PeekStats
import dev.skillbill.desktop.core.designsystem.detail.PeekState
import dev.skillbill.desktop.core.designsystem.detail.PeekTimelineRow
import dev.skillbill.desktop.core.designsystem.detail.PhasePeek
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import kotlin.time.Instant

@Composable
internal fun SubtaskPhaseCard(item: WorkItem, detail: Detail, subtask: WorkSubtask, card: PhaseCard, results: SubtaskPhaseResults?, activity: ActivityResult, renderedAt: Instant, onAction: (DetailAction) -> Unit) {
  val state = card.state.toPeekState()
  val model = listOfNotNull(card.model, card.effort).joinToString(" · ").ifBlank { null }
  val start = card.startedAt?.let { formatInstant(it, "d MMM HH:mm") }
  val duration = card.durationMillis?.let(::activityDuration)
  val path = subtaskSpecPath(detail, subtask.id)
  val phaseActivity = activityRows(activity, subtask.id, card)
  val footer = when (card.phaseId) {
    "plan" -> path?.let { PeekFooter("Open spec file", false) { onAction(DetailAction.OpenSpecFile(it)) } }
    "commit_push" -> item.branch?.let { PeekFooter("Open branch", true) { onAction(DetailAction.OpenBranch) } }
    else -> null
  }
  PhasePeek(
    title = phaseTitle(card.phaseId), phaseId = card.phaseId, state = state,
    word = if (card.state == PhaseCardState.Paused) "Paused" else null,
    agentId = card.agentId, model = model, round = card.round?.let { "round $it" }, startLabel = start,
    durationLabel = duration, why = if (card.state == PhaseCardState.Blocked) card.blockedReason else null,
    activity = phaseActivity, footer = footer,
  ) {
    val activityLabel = card.activityLabel
    val edit = card.edit
    if (card.state == PhaseCardState.Running || card.state == PhaseCardState.Blocked) {
      if (!activityLabel.isNullOrBlank() || edit != null || (card.state == PhaseCardState.Blocked && !card.blockedReason.isNullOrBlank())) PeekPart(if (card.state == PhaseCardState.Blocked) "Waiting on" else "Now", live = card.state == PhaseCardState.Running) {
        if (!activityLabel.isNullOrBlank()) PeekSignal(activityLabel, card.state == PhaseCardState.Running, LucideIcons.zap, ago = card.activityAt?.let { elapsedTextOrNull(it, renderedAt) })
        else if (card.state == PhaseCardState.Blocked) card.blockedReason?.let { PeekSignal(it, false, LucideIcons.warn) }
        edit?.let { PeekSignal("edited ${it.samplePaths.firstOrNull()?.let(::fileName) ?: "files"}", false, LucideIcons.pencil, keep = AnnotatedString("+${it.insertions} −${it.deletions}"), ago = elapsedTextOrNull(it.recordedAt, renderedAt)) }
      }
    }
    produced(card, results, path, onAction)
  }
}

@Composable
private fun produced(card: PhaseCard, results: SubtaskPhaseResults?, path: String?, onAction: (DetailAction) -> Unit) {
  when (card.phaseId) {
    "plan" -> path?.let { PeekPart("Produced") { PeekOutput(LucideIcons.fileText, fileName(it), onClick = { onAction(DetailAction.OpenSpecFile(it)) }, tooltip = it) } }
    "implement" -> {
      val edit = card.edit
      if (edit != null) PeekPart("Produced") { PeekStats(listOf(PeekStat(edit.fileCount.toString(), "files"), PeekStat("+${edit.insertions}", "added", PeekStatTone.Good), PeekStat("−${edit.deletions}", "removed", PeekStatTone.Bad))) }
      else if (card.files.isNotEmpty()) PeekPart("Produced") { card.files.forEach { PeekOutput(LucideIcons.file, it, tooltip = it) } }
    }
    "audit" -> results?.auditResult?.criteria?.takeIf { it.isNotEmpty() }?.let { criteria -> PeekPart("Criteria", end = "${criteria.count { it.met }} of ${criteria.size}") { criteria.forEach { PeekCheck("${it.ref} · ${it.text}", if (it.met) PeekCheckState.Ok else PeekCheckState.Bad) } } }
    "review" -> results?.let { PeekPart("Findings") { SubtaskReviewSection(it.reviewPasses, it.runningReviewPass) } }
    "validate" -> card.output?.let { output ->
      PeekPart("Validation") {
        PeekFacts(listOf("Verdict" to output.verdict))
        output.validationReceipt?.let { ValidationReceipt("Validation", it) }
        output.buildReceipt?.let { ValidationReceipt("Build", it) }
      }
    }
    "write_history" -> card.output?.historyReceipt?.let { receipt -> PeekPart("History") {
      PeekFacts(listOf("Changed paths" to receipt.changedPaths?.size?.toString(), "History written" to receipt.historyWritten?.toString(), "Decisions recorded" to receipt.decisionsRecorded?.toString()))
      receipt.changedPaths?.forEach { PeekOutput(LucideIcons.file, it, tooltip = it) }
    } }
    "commit_push" -> card.output?.let { output -> PeekPart("Commit") { PeekFacts(listOf("Commit" to output.commitSha?.take(8), "Branch" to output.pushedBranch)) } }
  }
}

@Composable
private fun ValidationReceipt(title: String, receipt: dev.skillbill.desktop.core.domain.model.ValidationResult) {
  PeekPart(title, end = receipt.status) {
    PeekFacts(listOf("Checks" to receipt.checks.size.toString()))
    receipt.checks.forEach { PeekOutput(LucideIcons.terminal, it, tooltip = it) }
    receipt.gateRunCount?.let { PeekFacts(listOf("Gate runs" to it.toString())) }
    receipt.gates.forEachIndexed { index, gate ->
      PeekPart("Gate ${index + 1}", end = gate.outcome) {
        PeekFacts(listOf("Duration" to activityDuration(gate.durationMs), "Cache" to gate.cacheMode, "Work units" to gate.executedWorkUnits.toString(), "Exit code" to gate.exitCode?.toString()))
        gate.executedChecks.forEach { PeekOutput(LucideIcons.check, it, tooltip = it) }
        gate.command?.let { dev.skillbill.desktop.core.designsystem.detail.PeekCmd(it) }
      }
    }
  }
}

private fun activityRows(activity: ActivityResult, subtaskId: Int, card: PhaseCard): List<PeekTimelineRow> {
  val listed = activity as? ActivityResult.Listed ?: return emptyList()
  val group = listed.groups.firstOrNull { it.subtaskId == subtaskId } ?: return emptyList()
  val rows = buildList<Pair<Instant, PeekTimelineRow>> {
    group.phases.filter { it.phaseId == card.phaseId }.forEach { phase ->
      phase.startedAt?.let { add(it to PeekTimelineRow(formatInstant(it, "HH:mm"), "Started · attempt ${phase.attempt}", null)) }
      phase.finishedAt?.let { add(it to PeekTimelineRow(formatInstant(it, "HH:mm"), "Settled · ${phase.verdict ?: phase.summary ?: "finished"}", null)) }
    }
    group.blocks.filter { it.phaseId == card.phaseId }.forEach { block -> block.reason?.let { add(block.at to PeekTimelineRow(formatInstant(block.at, "HH:mm"), it, PeekState.Wait)) } }
  }
  return rows.takeLast(5).map { it.second }
}
