package skillbill.application.workflow.service

import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.WorkflowPersistenceContext
import skillbill.application.workflow.persist.buildUpdateOk
import skillbill.contracts.JsonCodec
import skillbill.error.core.JsonFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import java.time.Clock
import java.time.ZoneOffset

internal class WorkflowServiceFeatureTaskAbandon(
  private val engine: WorkflowEngine,
  private val clock: Clock,
  private val repositoryCheckpointIdentity: () -> String = { "" },
) {
  fun abandonRuntimeFeatureTask(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    normalizedReason: String,
  ): WorkflowUpdateResult {
    val family = WorkflowFamily.TASK_RUNTIME
    if (family.definition.isTerminalStatus(existing.workflowStatus)) {
      return WorkflowUpdateResult.Error(
        existing.workflowId,
        "Runtime workflow '${existing.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val abandonedAt = clock.instant().atOffset(ZoneOffset.UTC).toString()
    val input =
      WorkflowUpdateInput(
        terminalInstant = clock.instant(),
        workflowStatus = WorkflowStatus.ABANDONED,
        currentStepId = existing.currentStepId.orEmpty(),
        stepUpdates = null,
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY to
                mapOf(
                  "reason" to normalizedReason,
                  "abandoned_at" to abandonedAt,
                ),
            ),
          ),
        sessionId = "",
      )
    val updated = engine.updateRecord(family.definition, existing, input)
    unitOfWork.workflowStates.save(family, updated)
    return buildUpdateOk(
      engine,
      family.definition,
      updated,
      input,
      WorkflowPersistenceContext(
        dbPath = unitOfWork.dbPath.toString(),
        repositoryCheckpointIdentity = repositoryCheckpointIdentity,
      ),
    )
  }

  fun abandonLegacyProseFeatureTask(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateRecord,
    normalizedReason: String,
  ): WorkflowUpdateResult {
    if (existing.workflowStatus.workflowStatus() in FEATURE_TASK_TERMINAL_STATUSES) {
      return WorkflowUpdateResult.Error(
        existing.workflowId,
        "Feature-task workflow '${existing.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
        unitOfWork.dbPath.toString(),
      )
    }
    val abandonedAt = clock.instant().atOffset(ZoneOffset.UTC).toString()
    val artifacts =
      try {
        JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(existing.artifactsJson))?.toMutableMap()
          ?: throw invalidWorkflowStateSchemaError("Legacy workflow artifacts must decode to an object.")
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.code == JsonFailureCode.MALFORMED_TEXT)
        throw invalidWorkflowStateSchemaError("Legacy workflow artifacts contain malformed JSON.", error)
      }
    artifacts[FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY] =
      mapOf(
        "reason" to normalizedReason,
        "abandoned_at" to abandonedAt,
      )
    val updated =
      existing.copy(
        workflowStatus = WorkflowStatus.ABANDONED.wireValue,
        artifactsJson = JsonCodec.mapToJsonString(artifacts),
        finishedAt = abandonedAt,
      )
    unitOfWork.workflowStates.terminalizeLegacyProseFeatureTaskWorkflow(updated)
    return WorkflowUpdateResult.Ok(
      workflowId = updated.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      acknowledgement =
        WorkflowUpdateAcknowledgementView(
          status = "ok",
          workflowId = updated.workflowId,
          workflowName = updated.workflowName,
          workflowStatus = WorkflowStatus.ABANDONED,
          currentStepId = updated.currentStepId,
          updatedStepIds = emptyList(),
          updatedArtifactKeys = listOf(FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY),
          readOnlyFullStateGuidance =
            "Update returns a compact acknowledgement. Use explicit read-only workflow get/show for full state, " +
              "including steps and the complete durable artifacts map.",
        ),
      launchProjection = null,
    )
  }
}
