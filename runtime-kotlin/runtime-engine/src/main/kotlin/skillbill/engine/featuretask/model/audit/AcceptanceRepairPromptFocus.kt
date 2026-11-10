package skillbill.engine.featuretask.model.audit

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState

public data class AcceptanceRepairPromptFocus internal constructor(
  public val canonicalRef: String,
  public val alias: String?,
  public val authoredText: String,
  public val openGaps: List<AcceptanceCriterionGap>,
  public val latestVerify: String?,
) {
  internal fun render(): String =
    buildString {
      append("Criterion:\n$canonicalRef")
      alias?.let { append(" (alias: $it)") }
      append("\n$authoredText\n\nOpen gaps:\n")
      append(openGaps.joinToString("\n") { "${it.id}: ${it.text} @ ${it.citation}" })
      append("\n\nLatest verify result:\n")
      append(latestVerify ?: "No verify has run for this criterion yet.")
    }

  internal companion object {
    const val OPERATOR_INSTRUCTIONS_PREFIX: String = "Operator instructions:"
    const val CHECKPOINT_GUIDANCE: String =
      "Earlier repair edits are committed in checkpoint commits. " +
        "A clean working tree does not mean the edits are missing."
    const val VERIFY_GUIDANCE: String =
      "Do not run build, compile, test, lint or gate commands. Verify runs next and is read-only."

    fun from(state: AcceptanceCriterionRepairState): AcceptanceRepairPromptFocus? {
      val criterion = state.criteria.singleOrNull { it.canonicalRef == state.currentCriterionRef } ?: return null
      val latestVerify =
        state.outcomes.lastOrNull { outcome ->
          val verifiedRevision = outcome.verifiedRevision
          outcome.criterionRef == criterion.canonicalRef &&
            outcome.kind == AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE &&
            verifiedRevision != null &&
            state.sourceTransitions.none {
              it.criterionRef == criterion.canonicalRef && it.revision > verifiedRevision
            }
        }?.evidenceRef
      return AcceptanceRepairPromptFocus(
        criterion.canonicalRef,
        criterion.originalAlias,
        criterion.originalText,
        criterion.gaps.filter { it.status == AcceptanceCriterionGapStatus.OPEN },
        latestVerify,
      )
    }
  }
}
