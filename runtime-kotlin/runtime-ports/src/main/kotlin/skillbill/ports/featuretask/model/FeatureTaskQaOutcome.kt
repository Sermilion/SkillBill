package skillbill.ports.featuretask.model

import skillbill.ports.idestatus.model.StandaloneQaOutcome

data class FeatureTaskQaOutcome(
  val workflowId: String,
  val outcome: StandaloneQaOutcome,
  val reason: String?,
  val qaFixAttempts: Int,
  val testedTreeIdentity: String?,
  val recordedAt: String,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskQaOutcome.workflowId must not be blank." }
    require(qaFixAttempts in 0..MAX_QA_FIX_ATTEMPTS) { "FeatureTaskQaOutcome.qaFixAttempts is out of range." }
    require(reason == null || reason.isNotBlank()) { "FeatureTaskQaOutcome.reason must not be blank." }
  }

  companion object {
    const val MAX_QA_FIX_ATTEMPTS: Int = 3
  }
}
