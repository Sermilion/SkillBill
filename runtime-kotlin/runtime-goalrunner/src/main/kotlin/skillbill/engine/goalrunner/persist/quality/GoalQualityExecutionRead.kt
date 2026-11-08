package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition

public data class GoalQualityAdmissionRequest(
  val baselineContentFingerprint: String,
  val governingInputs: GoalQualityExecutionInputs,
  val executionPlans: GoalQualityExecutionPlans,
  val validationSourceProof: GoalQualityValidationSourceProof? = null,
  val expectedRetainedRead: GoalQualityExecutionRead? = null,
)

public sealed interface GoalQualityExecutionRead {
  public data class Current(public val record: GoalQualityExecutionRecord) : GoalQualityExecutionRead

  public data class Historical(
    public val sourceContractVersion: String,
    public val originalPayload: Map<String, Any?>,
    public val parentWorkflowId: String?,
    public val invocationId: String?,
    public val baselineContentFingerprint: String?,
    public val selectionFingerprint: String?,
    public val governingInputFingerprint: String?,
    public val governingInputs: GoalQualityExecutionInputs?,
    public val qaPlan: String?,
    public val validationPlan: String?,
    public val planFingerprint: String?,
    public val progress: GoalQualityExecutionProgress?,
    public val status: GoalQualityExecutionStatus?,
    public val validation: GoalQualityValidationDisposition?,
    public val warning: String?,
    public val finalValidationContentFingerprint: String?,
    public val hasAuthoritativePlansAndProgress: Boolean,
    public val validationOccurrence: Int = 0,
    public val settledContentFingerprint: String? = null,
  ) : GoalQualityExecutionRead

  public data class Ambiguous(
    public val sourceContractVersion: String,
    public val originalPayload: Map<String, Any?>,
    public val invocationId: String?,
    public val retired: Boolean,
  ) : GoalQualityExecutionRead
}
