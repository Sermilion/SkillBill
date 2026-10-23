package skillbill.cli.work

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliFormat
import skillbill.contracts.workflow.identity.blockincident.WorkRecoverPayloadKeys
import skillbill.ports.work.WorkIncidentRecoveryCommands
import skillbill.ports.work.model.WorkIncidentRecoveryOutcome
import skillbill.ports.work.model.WorkRecoveryPreviewResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview

@Inject
class WorkRecoverCommand(
  private val controls: WorkIncidentRecoveryCommands,
  private val state: CliRunState,
) : DocumentedCliCommand("recover", "Preview or apply the typed recovery for a blocked work item's incident.") {
  private val id by argument("id", help = "Workflow id or standalone phase invocation id.")
  private val incident by option("--incident", help = "Block incident token to recover.").required()
  private val revision by option("--revision", help = "Incident revision the recovery was previewed against.").long()
  private val confirmedPrerequisites by option(
    "--confirm-prerequisite",
    help = "User-confirmed prerequisite kind. Repeat for each confirmed prerequisite.",
  ).choice(BlockIncidentPrerequisiteKind.entries.associateBy { it.wireValue }).multiple()
  private val agent by option("--agent", help = "Agent that runs the resumed work.")
  private val preview by option("--preview", help = "Show the recovery preview without applying it.").flag()
  private val format by option("--format", help = "Output format.")
    .choice("json" to CliFormat.JSON)
    .default(CliFormat.JSON)

  override fun run() {
    val payload =
      linkedMapOf<String, Any?>(
        WorkRecoverPayloadKeys.WORK_ID to id,
        WorkRecoverPayloadKeys.INCIDENT_TOKEN to incident,
      )
    val failed =
      if (preview) {
        previewPayload(controls.preview(id, incident, confirmedPrerequisites.toSet()), payload)
      } else {
        val incidentRevision = revision ?: throw UsageError("--revision is required unless --preview is set.")
        payload[WorkRecoverPayloadKeys.INCIDENT_REVISION] = incidentRevision
        val result =
          controls.recover(id, incident, incidentRevision, confirmedPrerequisites.toSet(), agent)
        payload += resultPayload(result)
        result is WorkIncidentRecoveryOutcome.Rejected ||
          result is WorkIncidentRecoveryOutcome.Recovered && result.status == BlockIncidentRecoveryAttemptStatus.FAILED
      }
    state.complete(payload, format, exitCode = if (failed) 1 else 0)
  }

  private fun previewPayload(
    result: WorkRecoveryPreviewResult,
    payload: MutableMap<String, Any?>,
  ): Boolean =
    when (result) {
      is WorkRecoveryPreviewResult.Available -> {
        payload[WorkRecoverPayloadKeys.RESULT] = WorkRecoverPayloadKeys.RESULT_AVAILABLE
        payload[WorkRecoverPayloadKeys.PREVIEW] = previewMap(result.preview)
        false
      }
      is WorkRecoveryPreviewResult.NeedsPrerequisites -> {
        payload[WorkRecoverPayloadKeys.RESULT] = WorkRecoverPayloadKeys.RESULT_NEEDS_PREREQUISITES
        payload[WorkRecoverPayloadKeys.PREREQUISITES] =
          result.instructions.map {
            linkedMapOf(
              WorkRecoverPayloadKeys.KIND to it.kind.wireValue,
              WorkRecoverPayloadKeys.INSTRUCTION to it.instruction,
            )
          }
        payload[WorkRecoverPayloadKeys.PLANNED_PREVIEW] = result.plannedPreview?.let(::previewMap)
        false
      }
      is WorkRecoveryPreviewResult.ExistingAttempt -> {
        payload[WorkRecoverPayloadKeys.RESULT] = WorkRecoverPayloadKeys.RESULT_EXISTING_ATTEMPT
        payload[WorkRecoverPayloadKeys.ATTEMPT_ID] = result.attemptId
        payload[WorkRecoverPayloadKeys.ATTEMPT_STATUS] = result.status.wireValue
        payload[WorkRecoverPayloadKeys.FAILURE] = result.failure?.wireValue
        false
      }
      is WorkRecoveryPreviewResult.Rejected -> {
        payload += rejectedPayload(result.reason, result.detail)
        true
      }
    }

  private fun previewMap(preview: BlockIncidentRecoveryPreview): Map<String, Any?> =
    linkedMapOf(
      WorkRecoverPayloadKeys.ACTION to preview.action,
      WorkRecoverPayloadKeys.LABEL to preview.label,
      WorkRecoverPayloadKeys.EFFECTS to preview.effects,
      WorkRecoverPayloadKeys.TARGET_WORKFLOW_ID to preview.targetWorkflowId,
      WorkRecoverPayloadKeys.TARGET_INVOCATION_ID to preview.targetInvocationId,
      WorkRecoverPayloadKeys.PHASE_ID to preview.phaseId,
      WorkRecoverPayloadKeys.INCIDENT_TOKEN to preview.incidentToken,
      WorkRecoverPayloadKeys.INCIDENT_REVISION to preview.incidentRevision,
    )

  private fun resultPayload(result: WorkIncidentRecoveryOutcome): Map<String, Any?> =
    when (result) {
      is WorkIncidentRecoveryOutcome.Recovered ->
        linkedMapOf(
          WorkRecoverPayloadKeys.RESULT to WorkRecoverPayloadKeys.RESULT_RECOVERED,
          WorkRecoverPayloadKeys.ATTEMPT_ID to result.attemptId,
          WorkRecoverPayloadKeys.ATTEMPT_STATUS to result.status.wireValue,
          WorkRecoverPayloadKeys.FAILURE to result.failure?.wireValue,
          WorkRecoverPayloadKeys.LAUNCH to result.launch?.let(::resultPayload),
        )
      is WorkIncidentRecoveryOutcome.Rejected -> rejectedPayload(result.reason, result.detail)
      is WorkIncidentRecoveryOutcome.LaunchFailed ->
        linkedMapOf(
          WorkRecoverPayloadKeys.RESULT to WorkRecoverPayloadKeys.RESULT_LAUNCH_FAILED,
          WorkRecoverPayloadKeys.REASON to result.reason,
        )
      else -> linkedMapOf(WorkRecoverPayloadKeys.RESULT to WorkRecoverPayloadKeys.RESULT_ACCEPTED)
    }

  private fun rejectedPayload(
    reason: String,
    detail: String?,
  ): Map<String, Any?> =
    linkedMapOf(
      WorkRecoverPayloadKeys.RESULT to WorkRecoverPayloadKeys.RESULT_REJECTED,
      WorkRecoverPayloadKeys.REASON to reason,
      WorkRecoverPayloadKeys.DETAIL to detail,
    )
}
