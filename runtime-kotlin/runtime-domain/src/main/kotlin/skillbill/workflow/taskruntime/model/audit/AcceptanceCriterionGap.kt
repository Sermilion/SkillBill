package skillbill.workflow.taskruntime.model.audit

data class AcceptanceCriterionGap(
  val id: String,
  val text: String,
  val citation: String,
  val status: AcceptanceCriterionGapStatus,
  val origin: AcceptanceCriterionGapOrigin,
  val closedRevision: Long?,
)

enum class AcceptanceCriterionGapStatus(val wireValue: String) {
  OPEN("open"),
  CLOSED("closed"),
}

enum class AcceptanceCriterionGapOrigin(val wireValue: String) {
  DISCOVERY("discovery"),
  REPAIR("repair"),
}

data class AcceptanceCriterionLateFinding(
  val criterionRef: String,
  val text: String,
  val citation: String?,
  val revision: Long,
)

internal object AcceptanceCriterionGapLimits {
  const val MAX_GAPS_PER_CRITERION: Int = 32
  const val MAX_TEXT_LENGTH: Int = 2048
  const val MAX_CITATION_LENGTH: Int = 512
  const val MAX_LATE_FINDINGS: Int = 256
  const val MAX_CHANGED_PATHS: Int = 512
  const val MAX_CHANGED_PATH_LENGTH: Int = 1024
  const val MIN_PLAN_GENERATION: Int = 0
  const val MAX_PLAN_GENERATION: Int = 2
}

internal fun AcceptanceCriterionRepairCriterion.closeOpenGaps(revision: Long): AcceptanceCriterionRepairCriterion =
  copy(
    gaps =
      gaps.map { gap ->
        if (gap.status == AcceptanceCriterionGapStatus.OPEN) {
          gap.copy(status = AcceptanceCriterionGapStatus.CLOSED, closedRevision = revision)
        } else {
          gap
        }
      },
  )
