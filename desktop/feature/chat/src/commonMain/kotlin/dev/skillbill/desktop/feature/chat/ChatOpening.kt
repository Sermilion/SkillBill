package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem

/** What a new chat opens on; the first match in declaration order wins. */
enum class ExecutionState { Blocked, Failed, Stalled, Running, Done, Stopped }

fun classify(item: WorkItem): ExecutionState = when {
  item.lifecycle == Lifecycle.Blocked || item.awaitsOperatorDecision -> ExecutionState.Blocked
  item.lifecycle == Lifecycle.Failed -> ExecutionState.Failed
  item.stalledSince != null -> ExecutionState.Stalled
  item.running || item.lifecycle == Lifecycle.Active -> ExecutionState.Running
  item.lifecycle == Lifecycle.Terminal -> ExecutionState.Done
  else -> ExecutionState.Stopped
}

/** The line shown before any process starts; it reads only the row, so it needs no detail read. */
fun stateLine(item: WorkItem): String {
  val phase = item.phaseExecution?.phaseId ?: item.stepLabel
  val reason = item.reason ?: item.exactPauseReason ?: "not recorded"
  return when (classify(item)) {
    ExecutionState.Blocked -> "Blocked on phase:$phase, reason: $reason"
    ExecutionState.Failed -> "Failed on phase:$phase, reason: $reason"
    ExecutionState.Stalled -> "Stalled on phase:$phase since ${item.stalledSince}"
    ExecutionState.Running -> "Running phase:$phase" + item.progress?.let { ", progress ${it.done}/${it.total}" }.orEmpty()
    ExecutionState.Done -> "Done after phase:$phase"
    ExecutionState.Stopped -> "${item.lifecycle.name} on phase:$phase, reason: $reason"
  }
}

fun openingInstruction(state: ExecutionState): String =
  "Keep your opening reply to 2 short sentences and at most 60 words. No headings, lists or detailed recap. " +
    when (state) {
      ExecutionState.Blocked -> "State the block reason and the best next action."
      ExecutionState.Failed -> "State the failure and the best next action."
      ExecutionState.Stalled -> "State why the execution is stuck and the best next action."
      ExecutionState.Running -> "State what is running and what happens next."
      ExecutionState.Done -> "State the outcome and any essential follow-up."
      ExecutionState.Stopped -> "State the current status and the best next action."
    }
