package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.engine.model.RequiredArtifactPresenceResolver
import skillbill.workflow.engine.model.ResolvedRequiredArtifact
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection

internal object FeatureTaskRuntimeRequiredArtifactPresenceResolver : RequiredArtifactPresenceResolver {
  override fun missingRequiredArtifacts(
    snapshot: WorkflowSnapshotView,
    resumeStepId: String,
    requiredArtifacts: List<String>,
  ): List<String> {
    if (requiredArtifacts.isEmpty()) {
      return emptyList()
    }
    val gateAdjustedRequired = qualityGateAdjustedRequiredArtifacts(snapshot, resumeStepId, requiredArtifacts)
    val completedPhaseIds = completedPhaseIds(snapshot)
    return gateAdjustedRequired.filterNot(completedPhaseIds::contains)
  }

  override fun resolveRequiredArtifact(
    snapshot: WorkflowSnapshotView,
    artifactKey: String,
  ): ResolvedRequiredArtifact {
    val record =
      decodePhaseRecords(snapshot)[artifactKey]
        ?: return ResolvedRequiredArtifact(present = false, value = null)
    if (record.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return ResolvedRequiredArtifact(present = false, value = null)
    }
    return ResolvedRequiredArtifact(
      present = true,
      value = record.outputArtifact ?: record.toArtifactMap(),
    )
  }

  private fun qualityGateAdjustedRequiredArtifacts(
    snapshot: WorkflowSnapshotView,
    resumeStepId: String,
    requiredArtifacts: List<String>,
  ): List<String> {
    if (
      resumeStepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY &&
      resumeStepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
    ) {
      return requiredArtifacts
    }
    val selection = goalContinuationQualityGateSelection(snapshot) ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE
    val gatePhase =
      when (selection) {
        FeatureTaskRuntimeQualityGateSelection.BUILD -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
        FeatureTaskRuntimeQualityGateSelection.VALIDATE -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
      }
    return requiredArtifacts.map { phaseId ->
      when (phaseId) {
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        -> gatePhase
        else -> phaseId
      }
    }
  }

  private fun goalContinuationQualityGateSelection(
    snapshot: WorkflowSnapshotView,
  ): FeatureTaskRuntimeQualityGateSelection? {
    val raw =
      snapshot.artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]
        ?: return null
    val rawMap =
      raw as? Map<*, *>
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime goal-continuation artifact must decode to an object.",
        )
    val continuationMap =
      JsonCodec.anyToStringAnyMap(rawMap)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime goal-continuation artifact must decode to an object with string keys.",
        )
    return FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(continuationMap).qualityGateSelection
  }

  private fun completedPhaseIds(snapshot: WorkflowSnapshotView): Set<String> =
    decodePhaseRecords(snapshot)
      .filterValues { record -> record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
      .keys

  private fun decodePhaseRecords(snapshot: WorkflowSnapshotView): Map<String, FeatureTaskRuntimePhaseRecord> {
    val raw = snapshot.artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] ?: return emptyMap()
    val rawMap =
      raw as? Map<*, *>
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' must decode to a map.",
        )
    return rawMap.entries.associate { (key, value) -> decodePhaseRecordEntry(key, value) }
  }

  private fun decodePhaseRecordEntry(
    key: Any?,
    value: Any?,
  ): Pair<String, FeatureTaskRuntimePhaseRecord> {
    val phaseId =
      key as? String
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' must have string keys; " +
            "found '$key'.",
        )
    val entryMap =
      JsonCodec.anyToStringAnyMap(value)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' entry for " +
            "'$phaseId' must decode to a map.",
        )
    return phaseId to FeatureTaskRuntimePhaseRecord.fromArtifactMap(entryMap)
  }
}
