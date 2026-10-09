package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.IdeStatusPayloadKeys
import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.IdeStatusSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProblem
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import java.util.logging.Level
import java.util.logging.Logger

private val ideStatusLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.IdeStatusSchemaValidator")

private class IdeStatusPayload(
  private val payload: Map<String, Any?>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> = payload
}

@Inject
class IdeStatusSchemaValidator : IdeStatusValidator {
  override fun validate(
    snapshot: IdeStatusSnapshot,
    sourceLabel: String,
  ) {
    validate(ideStatusSchemaWireMap(snapshot), sourceLabel)
  }

  override fun toWirePayload(snapshot: IdeStatusSnapshot): JsonPayloadContract =
    IdeStatusPayload(ideStatusSchemaWireMap(snapshot))

  fun validate(
    snapshot: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(snapshot)
    val errors = ClasspathContractSchemaLoader.validate(ideStatusSchema(), instance)
    if (errors.isEmpty()) return
    ideStatusLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw invalidIdeStatusSchemaError(
      sourceLabel = sourceLabel,
      fieldPath =
        goalObservabilityDottedFieldPath(
          sortedErrors.first().instanceLocation?.toString().orEmpty(),
        ),
      reason = formatValidationReason(sortedErrors, instance),
    )
  }

  companion object {
    private val canonical: IdeStatusSchemaValidator by lazy(::IdeStatusSchemaValidator)

    fun validate(
      snapshot: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validate(snapshot, sourceLabel)

    fun wireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> = ideStatusSchemaWireMap(snapshot)
  }

  private fun buildSchemaDriftLog(
    sourceLabel: String,
    errors: Set<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val parts =
      errors.sortedWith(violationOrdering).take(2).map { error ->
        val location = error.instanceLocation?.toString().orEmpty()
        val fieldPath = goalObservabilityDottedFieldPath(location).ifBlank { "<root>" }
        val offendingValue = extractGoalObservabilityOffendingValue(instance, location)
        if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
      }
    return "IDE status failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(
    sorted: List<ValidationMessage>,
    instance: JsonNode,
  ): String =
    sorted.joinToString(" | ") { error ->
      val instanceLocation = error.instanceLocation?.toString().orEmpty()
      val fieldPath = goalObservabilityDottedFieldPath(instanceLocation).ifBlank { "<root>" }
      val offendingValue = extractGoalObservabilityOffendingValue(instance, instanceLocation)
      buildString {
        append(fieldPath)
        append(": ")
        append(error.message)
        if (offendingValue.isNotBlank()) {
          append(" — offending value: ")
          append(offendingValue)
        }
      }
    }

  private val violationOrdering: Comparator<ValidationMessage> =
    compareBy(
      { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
      { it.instanceLocation?.toString().orEmpty() },
      { it.message.orEmpty() },
    )
}

internal const val IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE: String =
  IdeStatusSchemaPaths.CLASSPATH_RESOURCE

internal const val IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH: String =
  IdeStatusSchemaPaths.REPO_RELATIVE_PATH

private fun ideStatusSchemaWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
  buildMap {
    put(SharedPayloadKeys.CONTRACT_VERSION, snapshot.contractVersion)
    put("repository_identity", snapshot.repositoryIdentity)
    snapshot.branchCorrelation?.takeIf(String::isNotBlank)?.let {
      put(IdeStatusPayloadKeys.BRANCH_CORRELATION, it)
    }
    snapshot.issueKey?.takeIf(String::isNotBlank)?.let { put(SharedPayloadKeys.ISSUE_KEY, it) }
    snapshot.workflowId?.takeIf(String::isNotBlank)?.let { put(SharedPayloadKeys.WORKFLOW_ID, it) }
    snapshot.workflowFamily?.let { put("workflow_family", it.wireValue) }
    snapshot.execution?.let { execution -> putExecutionFields(execution) }
    put("lifecycle_state", snapshot.lifecycleState.wireValue)
    put("current_step", currentStepWireMap(snapshot))
    snapshot.progress?.let { put("progress", progressWireMap(it.completed, it.total)) }
    snapshot.startedAt?.let { put("started_at", it.toString()) }
    snapshot.currentSubtask?.let { put("current_subtask", currentSubtaskWireMap(it)) }
    snapshot.currentModel?.let { put("current_model", currentModelWireMap(it)) }
    snapshot.planning?.let { put("planning", ideStatusPlanningWireMap(it)) }
    snapshot.currentPhaseExecution?.let { put("current_phase_execution", phaseExecutionWireMap(it)) }
    putPauseFields(snapshot)
    putActivityFields(snapshot)
    put("updated_at", snapshot.updatedAt.toString())
    put("freshness", snapshot.freshness.wireValue)
    put(SharedPayloadKeys.SUMMARY, snapshot.summary)
    snapshot.problem?.let { put("problem", problemWireMap(it)) }
  }

private fun MutableMap<String, Any?>.putExecutionFields(execution: IdeStatusExecutionIdentity) {
  put(IdeStatusPayloadKeys.EXECUTION_SCOPE, execution.scope.wireValue)
  put(IdeStatusPayloadKeys.EXECUTION_ID, execution.executionId)
  put(IdeStatusPayloadKeys.STATUS_STORE_ID, execution.statusStoreId)
  put(IdeStatusPayloadKeys.RUN_SEQUENCE, execution.runSequence)
  put(IdeStatusPayloadKeys.STATUS_REVISION, execution.statusRevision)
  execution.invocationId?.let { put(IdeStatusPayloadKeys.INVOCATION_ID, it) }
  execution.phaseId?.let { put(SharedPayloadKeys.PHASE_ID, it) }
}

private fun currentStepWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
  linkedMapOf(
    "id" to snapshot.currentStep.id,
    "label" to snapshot.currentStep.label,
  )

private fun progressWireMap(
  completed: Int,
  total: Int,
): Map<String, Any?> =
  linkedMapOf(
    "completed" to completed,
    "total" to total,
  )

private fun currentSubtaskWireMap(subtask: IdeStatusCurrentSubtask): Map<String, Any?> =
  buildMap {
    put("id", subtask.id)
    subtask.startedAt?.let { put("started_at", it.toString()) }
    subtask.activeDurationMs?.let { put("active_duration_ms", it) }
    subtask.activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  }

private fun currentModelWireMap(model: IdeStatusCurrentModel): Map<String, Any?> =
  buildMap {
    put("model", model.model)
    model.effort?.let { put("effort", it) }
    model.phaseId?.let { put(SharedPayloadKeys.PHASE_ID, it) }
  }

private fun phaseExecutionWireMap(execution: IdeStatusCurrentPhaseExecution): Map<String, Any?> =
  buildMap {
    put(SharedPayloadKeys.PHASE_ID, execution.phaseId)
    put("kind", execution.kind.wireValue)
    put("count", execution.count)
    execution.total?.let { put("total", it) }
  }

private fun MutableMap<String, Any?>.putPauseFields(snapshot: IdeStatusSnapshot) {
  snapshot.pauseRequested?.takeIf { it }?.let { put("pause_requested", true) }
  snapshot.pausedAt?.let { put("paused_at", it.toString()) }
  snapshot.pauseReason?.let { reason ->
    put(
      "pause_reason",
      buildMap {
        put("code", reason.code.wireValue)
        reason.label?.let { put("label", it) }
      },
    )
  }
}

private fun MutableMap<String, Any?>.putActivityFields(snapshot: IdeStatusSnapshot) {
  snapshot.currentActivity?.let { put(IdeStatusPayloadKeys.CURRENT_ACTIVITY, it) }
  snapshot.activeDurationMs?.let { put("active_duration_ms", it) }
  snapshot.activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  val at = snapshot.lastAgentActivityAt
  val label = snapshot.lastAgentActivityLabel
  if (at != null && label != null) {
    put("last_agent_activity_at", at.toString())
    put("last_agent_activity_label", label.wireValue)
  }
}

private fun problemWireMap(problem: IdeStatusProblem): Map<String, Any?> =
  buildMap {
    put("code", problem.code.wireValue)
    put("message", problem.message)
    problem.details?.asWirePayload()?.toPayload()?.takeIf { it.isNotEmpty() }?.let { put("details", it) }
  }

private fun ideStatusPlanningWireMap(planning: IdeStatusPlanning): Map<String, Any?> =
  buildMap {
    put("state", planning.state.wireValue)
    put("shared_preplan_prepared", planning.sharedPreplanPrepared)
    put("planned_subtask_count", planning.plannedSubtaskCount)
    put("total_subtask_count", planning.totalSubtaskCount)
    planning.currentPlanningSubtaskId?.takeIf(String::isNotBlank)
      ?.let { put("current_planning_subtask_id", it) }
    planning.planningWaveSubtaskIds.takeIf { it.isNotEmpty() }
      ?.let { put("planning_wave_subtask_ids", it) }
    planning.reason?.takeIf(String::isNotBlank)?.let { put("reason", it) }
  }

private fun ideStatusSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = IdeStatusSchemaValidator::class.java.classLoader,
      classpathResource = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidIdeStatusSchemaError(
          sourceLabel = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason =
            "Canonical IDE status schema is missing. Expected classpath resource " +
              "'$IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidIdeStatusSchemaError(
          sourceLabel = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          ideStatusLog,
          "IDE status",
          IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
          IDE_STATUS_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = IdeStatusSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = IDE_STATUS_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidIdeStatusSchemaError(
          sourceLabel = IDE_STATUS_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "<schema>",
          reason = reason,
        )
      },
    ),
  )
