package skillbill.goalrunner

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError
import skillbill.goalrunner.model.GoalObservabilityProgressInput
import skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import skillbill.workflow.model.goalobservability.asGoalWorkflowArtifactMap
import skillbill.workflow.model.goalobservability.goalObservabilityHistoryFromArtifacts
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

object GoalObservabilityArtifacts {
  private data class RequiredProgressFields(
    val progressEvent: Map<*, *>,
    val issueKey: String,
    val subtaskId: Int,
    val timestamp: String,
  )

  fun patchForProgressEvent(input: GoalObservabilityProgressInput): FeatureTaskRuntimeWorkflowArtifactMap? =
    requiredProgressFields(input)?.let { fields ->
      patchForEvent(
        input.artifacts.asGoalWorkflowArtifactMap("goal observability progress input"),
      ) { sequenceNumber ->
        eventFrom(input, fields, sequenceNumber)
      }
    }

  fun patchForRuntimeEvent(input: GoalObservabilityRuntimeEventInput): FeatureTaskRuntimeWorkflowArtifactMap =
    patchForEvent(
      artifacts = input.artifacts.asGoalWorkflowArtifactMap("goal observability runtime event input"),
    ) { sequenceNumber ->
      GoalObservabilityEvent(
        issueKey = input.request.issueKey,
        subtaskId = input.request.subtaskId,
        workflowId = input.request.workflowId,
        workflowPhase = input.request.workflowPhase,
        workerRole = input.request.workerRole,
        livenessClass = input.request.livenessClass,
        activitySummary = input.request.activitySummary,
        timestamp = input.request.timestamp,
        sequenceNumber = sequenceNumber,
      )
    }

  private fun patchForEvent(
    artifacts: Map<String, Any?>,
    buildEvent: (Int) -> GoalObservabilityEvent,
  ): FeatureTaskRuntimeWorkflowArtifactMap {
    val existingHistory = goalObservabilityHistoryFromArtifacts(artifacts)
    val event = buildEvent(existingHistory.nextSequenceNumber())
    val history = existingHistory.append(event).toArtifactList()
    return FeatureTaskRuntimeWorkflowArtifactMap.from(
      linkedMapOf(
        GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY to event.toArtifactMap(),
        GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY to history,
      ),
    )
  }

  private fun requiredProgressFields(input: GoalObservabilityProgressInput): RequiredProgressFields? {
    val artifacts = input.artifacts.asGoalWorkflowArtifactMap("goal observability progress input")
    val progressEvent = artifacts["progress_event"] as? Map<*, *>
    val continuation = artifacts["goal_continuation"] as? Map<*, *>
    val issueKey = continuation?.get(SharedPayloadKeys.ISSUE_KEY)?.toString()?.takeIf(String::isNotBlank)
    val subtaskId =
      continuation?.get(SharedPayloadKeys.SUBTASK_ID)?.let { value ->
        value.asExactIntOrNull()
          ?: throw InvalidGoalObservabilityEventSchemaError(
            "goal observability progress input",
            SharedPayloadKeys.SUBTASK_ID,
            "must be an integer.",
          )
      }
    val timestamp = progressEvent?.get("timestamp")?.toString()?.takeIf(String::isNotBlank)
    return when {
      progressEvent == null -> null
      issueKey == null -> null
      subtaskId == null -> null
      timestamp == null -> null
      else -> RequiredProgressFields(progressEvent, issueKey, subtaskId, timestamp)
    }
  }

  private fun eventFrom(
    input: GoalObservabilityProgressInput,
    fields: RequiredProgressFields,
    sequenceNumber: Int,
  ): GoalObservabilityEvent {
    val progressEvent = fields.progressEvent
    val kind = progressEvent["kind"]?.toString()?.takeIf(String::isNotBlank) ?: "durable_progress"
    requireIntegerChildSequence(progressEvent)
    return GoalObservabilityEvent(
      issueKey = fields.issueKey,
      subtaskId = fields.subtaskId,
      workflowId = input.workflowId,
      workflowPhase =
        progressEvent[SharedPayloadKeys.STEP_ID]?.toString()?.takeIf(String::isNotBlank)
          ?: input.currentStepId.takeIf(String::isNotBlank)
          ?: "unknown",
      workerRole = progressEvent["source"]?.toString()?.takeIf(String::isNotBlank) ?: "unknown",
      livenessClass = kind,
      activitySummary =
        progressEvent["message"]?.toString()?.takeIf(String::isNotBlank)
          ?: "workflow_status=${input.workflowStatus}; progress_kind=$kind",
      timestamp = fields.timestamp,
      sequenceNumber = sequenceNumber,
      changedFileSummary = input.worktreeActivity?.changedFileSummary,
      diffStat = input.worktreeActivity?.diffStat,
    )
  }

  private fun requireIntegerChildSequence(progressEvent: Map<*, *>) {
    val sequence = progressEvent["sequence"] ?: return
    sequence.asExactIntOrNull()
      ?: throw InvalidGoalObservabilityEventSchemaError(
        "goal observability progress input",
        "sequence",
        "must be an integer.",
      )
  }
}
