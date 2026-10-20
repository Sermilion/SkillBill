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

data class QaFindingVerification(
  val findingId: String,
  val receiptRef: String,
  val observedTreeIdentity: String,
  val testedTreeIdentity: String,
  val requiredFlowIds: Set<String>,
  val passingFlowIds: Set<String>,
  val passingCriteria: Set<String>,
)

data class QaFindingClosureDecision(
  val closedFindingIds: Set<String>,
)

data class QaRepairState(
  val subtaskId: Int,
  val attempts: List<QaRepairAttempt> = emptyList(),
  val openFindings: List<QaOpenFinding> = emptyList(),
  val lastReceiptTreeIdentity: String? = null,
) {
  val consumedAttempts: Int get() = attempts.size

  val openFindingCriteria: Set<String> get() = openFindings.flatMap(QaOpenFinding::affectedCriteria).toSet()

  fun verifiedClosure(evidence: List<QaFindingVerification>): QaFindingClosureDecision =
    QaFindingClosureDecision(
      openFindings.filter { finding ->
        finding.receiptRefs.isNotEmpty() &&
          finding.receiptRefs.all { reference ->
            val verification = evidence.singleOrNull { it.findingId == finding.id && it.receiptRef == reference }
            verification != null && verification.observedTreeIdentity != verification.testedTreeIdentity &&
              (verification.requiredFlowIds.isNotEmpty() || finding.affectedCriteria.isNotEmpty()) &&
              verification.passingFlowIds.containsAll(verification.requiredFlowIds) &&
              verification.passingCriteria.containsAll(finding.affectedCriteria)
          }
      }.map(QaOpenFinding::id).toSet(),
    )

  fun observe(
    observations: List<QaOpenFinding>,
    closure: QaFindingClosureDecision,
    treeIdentity: String,
  ): QaRepairState {
    val retained = openFindings.associateBy(QaOpenFinding::id).toMutableMap()
    closure.closedFindingIds.forEach(retained::remove)
    observations.forEach { finding ->
      val existing = retained[finding.id]
      retained[finding.id] =
        if (existing == null) {
          finding
        } else {
          existing.copy(
            affectedCriteria = (existing.affectedCriteria + finding.affectedCriteria).distinct().sorted(),
            receiptRefs = (existing.receiptRefs + finding.receiptRefs).distinct().sorted(),
          )
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
    ): QaRepairState? {
      if (consumedAttempts <= 0) return null
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
      return QaRepairState(subtaskId = 1, attempts = attempts, lastReceiptTreeIdentity = testedTreeIdentity)
    }
  }
}
