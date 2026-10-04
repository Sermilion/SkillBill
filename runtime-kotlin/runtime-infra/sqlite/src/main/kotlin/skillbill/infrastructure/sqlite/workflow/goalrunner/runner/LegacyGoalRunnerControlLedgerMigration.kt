package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.infrastructure.sqlite.core.ops.recordMigrationNormalization
import skillbill.infrastructure.sqlite.workflow.toFeatureTaskWorkflowStateRecord
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.model.toSnapshot
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import java.sql.Connection

internal fun applyLegacyGoalRunnerControlLedgerMigration(
  connection: Connection,
  diagnostics: RuntimeDiagnostics,
) {
  val store = GoalRunnerControlStore(connection)
  connection.prepareStatement(
    """
    SELECT *
    FROM feature_task_workflows
    WHERE mode = 'runtime'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { rows ->
      while (rows.next()) {
        val workflow = rows.toFeatureTaskWorkflowStateRecord()
        val workflowId = workflow.workflowId
        val artifacts = workflow.toSnapshot().artifacts
        val legacyPolicy =
          if (store.reviewPolicy(workflowId) == null) reviewPolicyFromLegacyArtifacts(artifacts) else null
        val durableAcceptances = store.outOfBandAcceptances(workflowId)
        val legacyAcceptances =
          outOfBandAcceptancesFromLegacyArtifacts(artifacts)
            .filterKeys { subtaskId -> subtaskId !in durableAcceptances }
        val movedKeys = mutableListOf<String>()
        if (legacyPolicy != null) {
          store.persistReviewPolicy(workflowId, legacyPolicy)
          movedKeys += DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY.label()
        }
        if (legacyAcceptances.isNotEmpty()) {
          legacyAcceptances.values.forEach { acceptance ->
            store.persistOutOfBandAcceptance(workflowId, acceptance)
          }
          movedKeys += DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE.label()
        }
        if (movedKeys.isNotEmpty()) {
          diagnostics.recordMigrationNormalization(
            seam = "goal_runner_controls.legacy_artifacts",
            parentWorkflowId = workflowId,
            movedArtifactKeys = movedKeys,
          )
        }
      }
    }
  }
}

private fun reviewPolicyFromLegacyArtifacts(artifacts: Map<String, Any?>): GoalRunnerReviewPolicy? {
  val artifactFamily = DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY
  val raw = artifactFamily.value(artifacts) ?: return null
  val policy =
    JsonCodec.anyToStringAnyMap(raw)
      ?: goalRunnerControlSchemaError("legacy review policy artifact '${artifactFamily.label()}' must be a map.")
  val allowedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
    )
  policy.keys.forEach { key ->
    if (key !in allowedKeys) {
      goalRunnerControlSchemaError(
        "legacy review policy artifact '${artifactFamily.label()}' has unsupported field '$key'.",
      )
    }
  }
  val mode =
    policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE] as? String
      ?: goalRunnerControlSchemaError(
        "legacy review policy artifact '${artifactFamily.label()}' is missing code_review_mode.",
      )
  val codeReviewMode =
    try {
      CodeReviewExecutionMode.fromWire(mode)
    } catch (error: IllegalArgumentException) {
      goalRunnerControlSchemaError("legacy review policy artifact has invalid code_review_mode '$mode'.", error)
    }
  val agentAddonSelection =
    decodeLegacyAgentAddonSelection(
      policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION],
    )
  return GoalRunnerReviewPolicy(codeReviewMode, agentAddonSelection)
}

private fun outOfBandAcceptancesFromLegacyArtifacts(
  artifacts: Map<String, Any?>,
): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val artifactFamily = DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE
  val raw = artifactFamily.value(artifacts) ?: return emptyMap()
  val entries =
    raw as? List<*>
      ?: goalRunnerControlSchemaError("legacy acceptance artifact '${artifactFamily.label()}' must be a list.")
  return entries.associate { element ->
    val entry =
      JsonCodec.anyToStringAnyMap(element)
        ?: goalRunnerControlSchemaError(
          "legacy acceptance artifact '${artifactFamily.label()}' entries must be maps.",
        )
    val acceptance =
      GoalRunnerOutOfBandAcceptance(
        subtaskId =
          entry[SharedPayloadKeys.SUBTASK_ID].exactPositiveSubtaskIdOrNull()
            ?: goalRunnerControlSchemaError("legacy acceptance artifact entry subtask_id must be a positive integer."),
        commitSha = requiredLegacyAcceptanceString(entry, DecompositionManifestPayloadKeys.COMMIT_SHA),
        reason =
          requiredLegacyAcceptanceString(
            entry,
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON,
          ),
        acceptedAt =
          requiredLegacyAcceptanceString(entry, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT),
      )
    acceptance.subtaskId to acceptance
  }
}

private fun requiredLegacyAcceptanceString(
  entry: Map<String, Any?>,
  key: String,
): String =
  (entry[key] as? String)?.takeIf(String::isNotBlank)
    ?: goalRunnerControlSchemaError("legacy acceptance artifact entry is missing a nonblank $key.")

private fun legacyAddonSelectionError(
  message: String,
  cause: Throwable? = null,
): Nothing =
  throw SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, "Goal review policy $message", cause)

private fun decodeLegacyAgentAddonSelection(raw: Any?): AgentAddonSelection {
  val values = raw ?: return AgentAddonSelection()
  val entries = values as? List<*> ?: legacyAddonSelectionError("agent_addon_selection must be a list.")
  val decoded = entries.mapIndexed(::decodeLegacyAgentAddonSelectionEntry)
  return try {
    AgentAddonSelection(decoded)
  } catch (error: IllegalArgumentException) {
    legacyAddonSelectionError("agent_addon_selection is invalid: ${error.message}", error)
  }
}

private fun decodeLegacyAgentAddonSelectionEntry(
  index: Int,
  value: Any?,
): PersistedAgentAddonSelectionEntry {
  val entry =
    JsonCodec.anyToStringAnyMap(value)
      ?: legacyAddonSelectionError("agent_addon_selection entry $index must be a map.")
  val expectedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  if (entry.keys != expectedKeys) {
    legacyAddonSelectionError("agent_addon_selection entry $index has invalid fields.")
  }
  val slug =
    requiredLegacyAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG)
  val sourceIdentity =
    requiredLegacyAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY)
  val contentSha256 =
    requiredLegacyAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256)
  return try {
    PersistedAgentAddonSelectionEntry(slug, sourceIdentity, contentSha256)
  } catch (error: IllegalArgumentException) {
    legacyAddonSelectionError("agent_addon_selection entry $index is invalid: ${error.message}", error)
  }
}

private fun requiredLegacyAddonField(
  entry: Map<String, Any?>,
  index: Int,
  key: String,
): String = entry[key] as? String ?: legacyAddonSelectionError("add-on entry $index is missing $key.")
