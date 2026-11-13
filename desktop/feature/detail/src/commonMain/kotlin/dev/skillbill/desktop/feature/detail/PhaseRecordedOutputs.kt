package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.detail.PeekCmd
import dev.skillbill.desktop.core.designsystem.detail.PeekFacts
import dev.skillbill.desktop.core.designsystem.detail.PeekOutput
import dev.skillbill.desktop.core.designsystem.detail.PeekPart
import dev.skillbill.desktop.core.designsystem.detail.PeekStat
import dev.skillbill.desktop.core.designsystem.detail.PeekStatTone
import dev.skillbill.desktop.core.designsystem.detail.PeekStats
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseCardEdit
import dev.skillbill.desktop.core.domain.model.PhaseCardHistoryReceipt
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.selectableSubtaskIds

private const val FOLDED_VALIDATION_CHECKS = 6

@Composable
internal fun PreplanRecordedOutput(detail: Detail, onAction: (DetailAction) -> Unit) {
  val files = goalSpecFiles(detail)
  if (files.isEmpty()) return
  PeekPart("Goal spec") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      files.forEach { file ->
        PeekOutput(LucideIcons.fileText, fileName(file), onClick = { onAction(DetailAction.OpenSpecFile(file)) }, tooltip = file)
      }
      BasicText(
        "Shared by every subtask in this goal.",
        style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint),
      )
    }
  }
}

@Composable
internal fun PlanRecordedOutput(specPath: String?, onAction: (DetailAction) -> Unit) {
  specPath?.let {
    PeekPart("Produced") {
      PeekOutput(LucideIcons.fileText, fileName(it), onClick = { onAction(DetailAction.OpenSpecFile(it)) }, tooltip = it)
    }
  }
}

@Composable
internal fun EditRecordedOutput(edit: PhaseCardEdit?) {
  edit?.let {
    PeekPart("Produced") {
      PeekStats(
        listOf(
          PeekStat(it.fileCount.toString(), "files"),
          PeekStat("+${it.insertions}", "added", PeekStatTone.Good),
          PeekStat("−${it.deletions}", "removed", PeekStatTone.Bad),
        ),
      )
    }
  }
}

@Composable
internal fun BuildRecordedOutput(receipt: ValidationResult?) {
  receipt ?: return
  PeekPart("Build", end = receipt.status) {
    receipt.gates.firstOrNull()?.command?.let { PeekCmd(it) }
    PeekFacts(
      listOfNotNull(
        "Result" to receipt.status,
        receipt.gateRunCount?.let { "Gate runs" to it.toString() },
      ),
    )
    ValidationGateRuns(receipt)
  }
}

@Composable
internal fun ValidateRecordedOutput(
  output: PhaseCardOutput,
  subtaskId: Int?,
  detail: Detail,
  checksExpanded: Boolean,
  onToggleChecks: () -> Unit,
) {
  val receipt = output.validationReceipt ?: return
  val verdict = output.verdict ?: receipt.status
  val evidence =
    subtaskId?.let { id -> detail.validation.firstOrNull { it.subtaskId == id }?.evidence }.orEmpty()
  PeekPart("Validation", end = receipt.status) {
    PeekFacts(listOf("Verdict" to verdict))
    ValidationChecks(receipt, checksExpanded, onToggleChecks)
    receipt.gateRunCount?.let { PeekFacts(listOf("Gate runs" to it.toString())) }
    ValidationGateRuns(receipt)
    evidence.takeIf { it.isNotEmpty() }?.let { commands ->
      PeekPart("Command evidence") {
        commands.forEach { cmd ->
          val line = listOfNotNull(cmd.command, cmd.exitCode?.let { "exit $it" }).joinToString(" · ")
          PeekOutput(LucideIcons.terminal, line, tooltip = line)
        }
      }
    }
  }
}

@Composable
private fun ValidationChecks(receipt: ValidationResult, expanded: Boolean, onToggle: () -> Unit) {
  val checks = receipt.checks
  if (checks.isEmpty()) return
  val visible = if (expanded) checks else checks.take(FOLDED_VALIDATION_CHECKS)
  visible.forEach { PeekOutput(LucideIcons.terminal, it, tooltip = it) }
  if (checks.size > FOLDED_VALIDATION_CHECKS) {
    val remaining = checks.size - FOLDED_VALIDATION_CHECKS
    SbButton(
      if (expanded) "Show fewer" else "Show $remaining more checks",
      onToggle,
      size = SbControlSize.Small,
      icon = if (expanded) LucideIcons.chevronUp else LucideIcons.chevronDown,
    )
  }
}

@Composable
private fun ValidationGateRuns(receipt: ValidationResult) {
  receipt.gates.forEachIndexed { index, gate ->
    PeekPart("Gate ${index + 1}", end = gate.outcome) {
      PeekFacts(
        listOfNotNull(
          "Duration" to activityDuration(gate.durationMs),
          "Cache" to gate.cacheMode,
          "Work units" to gate.executedWorkUnits.toString(),
          gate.exitCode?.let { "Exit code" to it.toString() },
        ),
      )
      gate.executedChecks.forEach { PeekOutput(LucideIcons.check, it, tooltip = it) }
      gate.command?.let { PeekCmd(it) }
    }
  }
}

@Composable
internal fun HistoryRecordedOutput(receipt: PhaseCardHistoryReceipt, detail: Detail, item: WorkItem, onAction: (DetailAction) -> Unit) {
  PeekPart("History") {
    PeekFacts(
      listOfNotNull(
        receipt.historyWritten?.let { "History written" to if (it) "yes" else "no" },
        receipt.decisionsRecorded?.let { "Decisions recorded" to if (it) "decisions recorded" else "unchanged" },
      ),
    )
    receipt.changedPaths?.forEach { path ->
      val resolved = resolveRecordedReceiptPath(path, detail, item)
      if (resolved?.openable == true) {
        PeekOutput(LucideIcons.file, fileName(path), onClick = { onAction(DetailAction.OpenSpecFile(resolved.absolute)) }, tooltip = resolved.absolute)
      } else {
        PeekOutput(LucideIcons.file, fileName(path), tooltip = path)
      }
    }
  }
}

@Composable
internal fun CommitRecordedOutput(output: PhaseCardOutput) {
  PeekPart("Commit") {
    PeekFacts(listOfNotNull(output.commitSha?.let { "Commit" to it.take(8) }, output.pushedBranch?.let { "Branch" to it }))
  }
}

internal fun implementChangesEligible(changes: ChangesUi, subtaskId: Int): Boolean {
  val listed = changes.subtasks as? SubtaskScopesState.Listed ?: return false
  return subtaskId in selectableSubtaskIds(listed.subtasks)
}
