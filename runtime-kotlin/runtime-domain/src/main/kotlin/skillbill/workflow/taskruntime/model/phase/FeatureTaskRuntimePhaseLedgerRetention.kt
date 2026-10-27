package skillbill.workflow.taskruntime.model.phase

fun retainPhaseLedgerEvidence(
  entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  limit: Int,
): List<FeatureTaskRuntimePhaseLedgerEntry> {
  require(limit > 0)
  val ordered = entries.sortedBy { it.sequenceNumber }
  if (ordered.size <= limit) return ordered

  val anchors = phaseLedgerEvidenceAnchors(ordered)
  check(anchors.size <= limit) {
    "Phase ledger limit $limit cannot retain ${anchors.size} retry and settlement anchors."
  }
  val retainedSequences = anchors.mapTo(mutableSetOf()) { it.sequenceNumber }
  val recent =
    ordered.asReversed().filter { it.sequenceNumber !in retainedSequences }
      .take(limit - retainedSequences.size)
  return (anchors + recent).sortedBy { it.sequenceNumber }
}

fun isRetainedPhaseLedgerEvidenceWindow(
  entries: List<FeatureTaskRuntimePhaseLedgerEntry>,
  limit: Int,
): Boolean {
  if (entries.isEmpty() || limit < 1 || entries.size > limit) return false
  val pairs = entries.zipWithNext()
  if (pairs.any { (previous, next) -> previous.sequenceNumber >= next.sequenceNumber }) return false
  val gaps = pairs.filter { (previous, next) -> next.sequenceNumber.toLong() != previous.sequenceNumber.toLong() + 1 }
  return when {
    entries.first().sequenceNumber == 0 && gaps.isEmpty() -> true
    entries.size != limit -> false
    gaps.isEmpty() -> true
    else -> {
      val recentStart = gaps.last().second.sequenceNumber
      val anchors = phaseLedgerEvidenceAnchors(entries).mapTo(mutableSetOf()) { it.sequenceNumber }
      entries.takeWhile { it.sequenceNumber < recentStart }.all { it.sequenceNumber in anchors }
    }
  }
}

private fun phaseLedgerEvidenceAnchors(
  ordered: List<FeatureTaskRuntimePhaseLedgerEntry>,
): List<FeatureTaskRuntimePhaseLedgerEntry> {
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
  return (retryAnchors + settlementAnchors).distinctBy { it.sequenceNumber }
}
