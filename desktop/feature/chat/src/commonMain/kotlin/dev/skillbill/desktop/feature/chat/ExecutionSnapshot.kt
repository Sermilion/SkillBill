package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.WorkItem

private const val VALUE_CAP = 2000
private const val EDIT_PATH_CAP = 10

data class SnapshotEntry(val key: String, val value: String)

/** The execution as the agent sees it, in a fixed key order. Per-read timestamps, durations and deadlines are never keys. */
data class ExecutionSnapshot(val entries: List<SnapshotEntry>)

data class ChangedEntry(val key: String, val old: String, val new: String)

data class SnapshotDiff(val changed: List<ChangedEntry>, val added: List<SnapshotEntry>, val removed: List<String>)

fun snapshot(item: WorkItem, detail: Detail?): ExecutionSnapshot {
  val entries = mutableListOf<SnapshotEntry>()
  entries.put("execution.id", item.id)
  entries.put("execution.kind", item.kind?.name)
  entries.put("execution.issueKey", item.issueKey)
  entries.put("execution.title", item.title)
  entries.put("execution.lifecycle", item.lifecycle.name)
  entries.put("execution.running", item.running)
  entries.put("execution.pauseRequested", item.pauseRequested)
  entries.put("execution.awaitsOperatorDecision", item.awaitsOperatorDecision)
  entries.put("execution.reason", item.reason)
  entries.put("execution.exactPauseReason", item.exactPauseReason)
  entries.put("execution.stalledSince", item.stalledSince)
  entries.put("execution.step", item.stepLabel)
  entries.put("execution.phaseExecution", item.phaseExecutionLabel)
  entries.put("execution.progress", item.progress?.let { "${it.done}/${it.total}" })
  entries.put("execution.branch", item.branch)
  entries.put("execution.worktree", item.repositoryRoot)
  entries.put("execution.agent", item.agentId)
  entries.put("execution.model", item.model)
  entries.put("execution.effort", item.effort)
  entries.put("execution.currentSubtaskId", item.currentSubtaskId)
  if (detail != null) entries.putDetail(detail)
  return ExecutionSnapshot(entries)
}

private fun MutableList<SnapshotEntry>.putDetail(detail: Detail) {
  put("detail.currentPhase", detail.currentPhase)
  put("detail.workflowId", detail.workflowId)
  put("detail.invocationId", detail.invocationId)
  put("detail.activeAgent", detail.activeAgent)
  put("detail.executionLiveness", detail.executionLiveness)
  put(
    "detail.lastAgentActivity",
    listOfNotNull(detail.lastAgentActivityLabel, detail.lastAgentActivityAt?.let { "at $it" }).joinToString(" ").ifEmpty { null },
  )
  put("detail.latestActivity", latestActivity(detail))
  detail.subtasks.forEach { subtask ->
    put("detail.subtask.${subtask.id}", "${subtask.name}: ${subtask.status}" + subtask.blockedReason?.let { ", blocked: $it" }.orEmpty())
  }
  detail.phases.forEach { put("detail.phase.${it.id}", "${it.status}, attempts ${it.attemptCount}") }
  put(
    "detail.incident",
    detail.incident?.let { incident ->
      incident.summary + incident.cause?.let { "; cause: $it" }.orEmpty() + "; next: ${incident.nextAction}"
    },
  )
  val findings = detail.phaseResults.flatMap { it.reviewPasses }.flatMap { it.findings }.map { finding ->
    "[${finding.severity}] " + finding.location?.let { "$it " }.orEmpty() + finding.description + " (${finding.status.name})"
  } +
    detail.goalQa?.findings.orEmpty().map { "[qa] ${it.title}" + if (it.fixed) " (fixed)" else "" } +
    detail.goalAssessment?.findings.orEmpty().map { "[${it.severity}] ${it.path}:${it.line} ${it.failureScenario} (${it.state.name})" }
  findings.forEachIndexed { index, finding -> put("detail.finding.${index + 1}", finding) }
  detail.validation.forEachIndexed { index, validation ->
    put(
      "detail.validation.${index + 1}",
      "subtask ${validation.subtaskId}: passed=${validation.passed}" +
        validation.integrityProblem?.let { ", problem: $it" }.orEmpty() +
        validation.result?.let { ", status: ${it.status}" }.orEmpty(),
    )
  }
  put("detail.spec.parent", detail.goalSpecBundle?.parentSpecPath)
  detail.goalSpecBundle?.subtasks.orEmpty().forEach { put("detail.spec.subtask.${it.id}", it.specPath) }
}

private fun latestActivity(detail: Detail): String? {
  val event = detail.latestActivity.event?.let { listOfNotNull(it.workflowPhase, it.workerRole, it.summary, it.at?.toString()).joinToString(" ") }
  val edit = detail.latestActivity.edit?.let { edit ->
    "edited ${edit.paths.size} files (+${edit.insertions ?: 0}/-${edit.deletions ?: 0})" +
      edit.phaseId?.let { " in $it" }.orEmpty() +
      edit.paths.take(EDIT_PATH_CAP).joinToString(", ").ifEmpty { null }?.let { ": $it" }.orEmpty()
  }
  return listOfNotNull(event, edit).joinToString("; ").ifEmpty { null }
}

private fun MutableList<SnapshotEntry>.put(key: String, value: Any?) {
  if (value != null) add(SnapshotEntry(key, value.toString().take(VALUE_CAP)))
}

fun ExecutionSnapshot.render(): String = (listOf("Execution context:") + entries.map { "${it.key}: ${it.value}" }).joinToString("\n")

/** Changed and added keys in [current] order, then removed keys in [previous] order. */
fun diff(previous: ExecutionSnapshot, current: ExecutionSnapshot): SnapshotDiff {
  val before = previous.entries.associate { it.key to it.value }
  val after = current.entries.associate { it.key to it.value }
  val changed = current.entries.mapNotNull { entry -> before[entry.key]?.takeIf { it != entry.value }?.let { ChangedEntry(entry.key, it, entry.value) } }
  val added = current.entries.filter { it.key !in before }
  val removed = previous.entries.map { it.key }.filter { it !in after }
  return SnapshotDiff(changed, added, removed)
}

fun SnapshotDiff.render(): String {
  if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) return "No execution changes since your last turn."
  val lines = changed.map { "~ ${it.key}: ${it.old} -> ${it.new}" } + added.map { "+ ${it.key}: ${it.value}" } + removed.map { "- $it" }
  return (listOf("Execution changes since your last turn:") + lines).joinToString("\n")
}
