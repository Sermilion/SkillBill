package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.SubtaskScopesState
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.selectableSubtaskIds

private const val FOLDED_VALIDATION_CHECKS = 6

@Composable
internal fun EditRecordedOutput(edit: PhaseCardEdit?, output: PhaseCardOutput? = null, simplified: Boolean = false) {
  if (edit != null || !output?.summary.isNullOrBlank()) {
    PeekPart(if (simplified) "Simplified" else "Produced") {
      output?.summary?.takeIf(String::isNotBlank)?.let {
        BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
      }
      edit?.let {
        PeekStats(
          listOf(
            PeekStat(it.fileCount.toString(), "files"),
            PeekStat("+${it.insertions}", "added", PeekStatTone.Good),
            PeekStat("−${it.deletions}", "removed", PeekStatTone.Bad),
          ),
        )
      }
    }
  } else {
    PeekPart(if (simplified) "Simplified" else "Produced") {
      BasicText("No change summary was retained for this phase.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}

@Composable
internal fun BuildRecordedOutput(receipt: ValidationResult?, state: PhaseCardState) {
  if (receipt == null) {
    MissingGateReceipt("Build", state)
    return
  }
  PeekPart("Build", end = receipt.status) {
    receipt.gates.firstOrNull()?.command?.let { PeekCmd(it) }
    PeekFacts(
      listOfNotNull(
        "Result" to receipt.status,
        receipt.gateRunCount?.let { "Gate runs" to it.toString() },
      ),
    )
    PlanningFold("Gate details") { ValidationGateRuns(receipt) }
  }
}

@Composable
internal fun ValidateRecordedOutput(
  output: PhaseCardOutput?,
  state: PhaseCardState,
  subtaskId: Int?,
  detail: Detail,
  checksExpanded: Boolean,
  onToggleChecks: () -> Unit,
) {
  val receipt = output?.validationReceipt
  if (receipt == null) {
    MissingGateReceipt("Validation", state)
    return
  }
  val verdict = output.verdict ?: receipt.status
  val evidence =
    subtaskId?.let { id -> detail.validation.firstOrNull { it.subtaskId == id }?.evidence }.orEmpty()
  PeekPart("Validation", end = receipt.status) {
    PeekFacts(listOf("Verdict" to verdict, "Checks" to receipt.checks.size.toString()))
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
private fun MissingGateReceipt(label: String, state: PhaseCardState) {
  val message = when (state) {
    PhaseCardState.Running -> "$label is running. Results will appear when it finishes."
    PhaseCardState.Paused, PhaseCardState.Blocked -> "$label has not produced a result yet."
    PhaseCardState.Skipped -> "$label was skipped."
    PhaseCardState.Failed -> "$label ended without a recorded result."
    PhaseCardState.Done -> "The ${label.lowercase()} receipt was not retained for this run."
  }
  PeekPart(label) {
    BasicText(message, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
  }
}

@Composable
private fun ValidationChecks(receipt: ValidationResult, expanded: Boolean, onToggle: () -> Unit) {
  val checks = receipt.checks
  if (checks.isEmpty()) return
  val visible = if (expanded) checks else checks.take(FOLDED_VALIDATION_CHECKS)
  visible.forEach { name ->
    val gate = receipt.gates.firstOrNull { name in it.executedChecks }
    dev.skillbill.desktop.core.designsystem.detail.PeekCheck(
      name,
      if (gate?.exitCode == 0 || gate?.outcome == "passed") dev.skillbill.desktop.core.designsystem.detail.PeekCheckState.Ok
      else if (gate?.exitCode != null) dev.skillbill.desktop.core.designsystem.detail.PeekCheckState.Bad
      else dev.skillbill.desktop.core.designsystem.detail.PeekCheckState.Waiting,
    )
  }
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
      gate.executedChecks.filterNot { it in receipt.checks }.forEach { PeekOutput(LucideIcons.check, it, tooltip = it) }
      gate.command?.let { PeekCmd(it) }
    }
  }
}

@Composable
internal fun HistoryRecordedOutput(receipt: PhaseCardHistoryReceipt, detail: Detail, item: WorkItem, onAction: (DetailAction) -> Unit) {
  PeekPart("History") {
    PeekFacts(
      listOfNotNull(
        receipt.historyWritten?.let { "History" to if (it) "written" else "unchanged" },
        receipt.decisionsRecorded?.let { "Decisions" to if (it) "recorded" else "unchanged" },
      ),
    )
    receipt.changedPaths?.forEach { path ->
      val resolved = resolveRecordedReceiptPath(path, detail, item)
      if (resolved?.openable == true) {
        PeekOutput(LucideIcons.file, path, onClick = { onAction(DetailAction.OpenSpecFile(resolved.absolute)) }, tooltip = resolved.absolute)
      } else {
        PeekOutput(LucideIcons.file, path, tooltip = path)
      }
    }
  }
}

@Composable
internal fun CommitRecordedOutput(output: PhaseCardOutput) {
  PeekPart("Commit") {
    PeekFacts(listOfNotNull(output.commitSha?.let { "Commit" to it.take(8) }, output.pushedBranch?.let { "Branch" to it }))
    if (output.commitSha == null && output.pushedBranch == null) {
      BasicText("No commit receipt was retained for this phase.", style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}

internal fun implementChangesEligible(changes: ChangesUi, subtaskId: Int): Boolean {
  val listed = changes.subtasks as? SubtaskScopesState.Listed ?: return false
  return subtaskId in selectableSubtaskIds(listed.subtasks)
}
