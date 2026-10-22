package skillbill.workflow.taskruntime.model.phase

fun retainPhaseLedgerEvidence(
  entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  limit: Int,
): List<FeatureTaskRuntimePhaseLedgerEntry> {
  require(limit > 0)
  val ordered = entries.sortedBy { it.sequenceNumber }
  if (ordered.size <= limit) return ordered

  val retriesByPhase =
    ordered
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY }
      .groupBy { it.phaseId }
      .values
  val retryAnchors =
    retriesByPhase.flatMap { retries ->
      listOfNotNull(
        retries.lastOrNull { it.recoveryAttemptId == null },
        retries.lastOrNull { it.recoveryAttemptId != null && it.preserveBudget },
      )
    }
  val settlementAnchors =
    retryAnchors.mapNotNull { retry ->
      ordered.lastOrNull { entry ->
        entry.phaseId == retry.phaseId && entry.sequenceNumber > retry.sequenceNumber &&
          entry.action in
          setOf(FeatureTaskRuntimePhaseLedgerAction.BLOCKED, FeatureTaskRuntimePhaseLedgerAction.COMPLETE)
      }
    }
  val anchors = (retryAnchors + settlementAnchors).distinctBy { it.sequenceNumber }
  check(anchors.size <= limit) {
    "Phase ledger limit $limit cannot retain ${anchors.size} retry and settlement anchors."
  }
  val retainedSequences = anchors.mapTo(mutableSetOf()) { it.sequenceNumber }
  val recent =
    ordered.asReversed().filter { it.sequenceNumber !in retainedSequences }
      .take(limit - retainedSequences.size)
  return (anchors + recent).sortedBy { it.sequenceNumber }
}
