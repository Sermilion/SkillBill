package skillbill.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

data class FeatureTaskRuntimeCommitPushResultArtifact(
  val commitSha: String?,
  val preCommitProjection: Boolean,
)

fun DurableWorkflowArtifacts.goalContinuationOutcomeArtifact(): FeatureTaskRuntimeGoalContinuationOutcome? {
  if (!containsKey(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY)) return null
  val raw =
    JsonCodec.anyToStringAnyMap(this[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY])
      ?: throw InvalidWorkflowStateSchemaError("Goal-continuation outcome artifact must decode to an object.")
  return FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(raw)
}

fun DurableWorkflowArtifacts.commitPushResultArtifact(): FeatureTaskRuntimeCommitPushResultArtifact? {
  if (!containsKey(DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT)) return null
  val raw =
    JsonCodec.anyToStringAnyMap(this[DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT])
      ?: throw InvalidWorkflowStateSchemaError("Commit-push result artifact must decode to an object.")
  val reader = durableArtifactMapReader(raw)
  return FeatureTaskRuntimeCommitPushResultArtifact(
    commitSha = reader.optionalString(DecompositionManifestPayloadKeys.COMMIT_SHA),
    preCommitProjection = reader.optionalBoolean(DecompositionManifestPayloadKeys.PRE_COMMIT_PROJECTION) ?: false,
  )
}
