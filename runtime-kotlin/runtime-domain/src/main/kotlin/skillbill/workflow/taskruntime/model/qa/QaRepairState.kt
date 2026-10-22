package skillbill.workflow.taskruntime.model.qa

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

data class QaRepairAttempt(
  val attemptNumber: Int,
  val receiptTreeIdentity: String,
  val consumedFindingIds: List<String>,
  val repairOutputRef: String,
  val outcome: QaRepairAttemptOutcome,
)

enum class QaRepairAttemptOutcome(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
}

data class QaOpenFinding(
  val id: String,
  val affectedCriteria: List<String>,
  val receiptRefs: List<String>,
)

data class QaRepairState(
  val subtaskId: Int,
  val attempts: List<QaRepairAttempt> = emptyList(),
  val openFindings: List<QaOpenFinding> = emptyList(),
  val lastReceiptTreeIdentity: String? = null,
) {
  val consumedAttempts: Int get() = attempts.size

  val openFindingCriteria: Set<String> get() = openFindings.flatMap(QaOpenFinding::affectedCriteria).toSet()

  fun observe(
    observations: List<QaOpenFinding>,
    conclusive: Boolean,
    treeIdentity: String,
  ): QaRepairState {
    val retained = openFindings.associateBy(QaOpenFinding::id).toMutableMap()
    if (conclusive) retained.keys.retainAll(observations.map(QaOpenFinding::id).toSet())
    observations.forEach { finding ->
      val existing = retained[finding.id]
      retained[finding.id] =
        if (existing == null) {
          finding
        } else {
          existing.copy(receiptRefs = (existing.receiptRefs + finding.receiptRefs).distinct())
        }
    }
    return copy(
      openFindings = retained.values.sortedBy(QaOpenFinding::id),
      lastReceiptTreeIdentity = treeIdentity,
    )
  }

  fun recordRepair(attempt: QaRepairAttempt): QaRepairState {
    require(attempt.attemptNumber == attempts.size + 1)
    require(attempt.attemptNumber <= MAX_ATTEMPTS)
    return copy(attempts = attempts + attempt)
  }

  companion object {
    const val MAX_ATTEMPTS = 3
    private const val UNRECORDED_TREE = "unrecorded"

    fun repairOutputRef(
      workflowId: String,
      attemptNumber: Int,
    ): String = "$workflowId:${FeatureTaskRuntimePhaseIds.QA_FIX}:attempt-$attemptNumber"

    fun resumedAfter(
      workflowId: String,
      consumedAttempts: Int,
      testedTreeIdentity: String?,
      openFindings: List<QaOpenFinding>,
    ): QaRepairState? {
      if (consumedAttempts <= 0 && openFindings.isEmpty()) return null
      val attempts =
        (1..consumedAttempts.coerceAtMost(MAX_ATTEMPTS)).map { number ->
          QaRepairAttempt(
            attemptNumber = number,
            receiptTreeIdentity = testedTreeIdentity ?: UNRECORDED_TREE,
            consumedFindingIds = emptyList(),
            repairOutputRef = repairOutputRef(workflowId, number),
            outcome = QaRepairAttemptOutcome.COMPLETED,
          )
        }
      return QaRepairState(
        subtaskId = 1,
        attempts = attempts,
        openFindings = openFindings,
        lastReceiptTreeIdentity = testedTreeIdentity,
      )
    }
  }
}
