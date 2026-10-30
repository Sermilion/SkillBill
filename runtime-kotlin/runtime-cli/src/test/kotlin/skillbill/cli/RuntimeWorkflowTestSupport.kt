package skillbill.cli

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.cli.kernel.payload.toPayload
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.workflow.toCliMap
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.workflow.payload.WorkflowArtifactKeys
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.create
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertIs

internal fun installFakeRuntimeMcpBin(home: Path): Path {
  val bin = home.resolve(".skill-bill").resolve("runtime").resolve("runtime-mcp").resolve("bin").resolve("runtime-mcp")
  Files.createDirectories(bin.parent)
  Files.writeString(bin, "#!/bin/sh\nexit 0\n")
  Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"))
  return bin
}

internal object RuntimeWorkflowTestSupport {
  fun open(
    dbPath: Path,
    context: CliRuntimeContext,
  ): Map<String, Any?> {
    val runtime = component(context, dbPath)
    val service = runtime.workflowService
    val result =
      service.open(
        WorkflowServiceOpenArgs(
          kind = WorkflowFamilyKind.TASK_RUNTIME,
        ),
      )
    return assertIs<WorkflowOpenResult.Ok>(result).toCliMap(runtime.workflowArtifactWireEncoder)
  }

  data class UpdateArgs(
    val dbPath: Path,
    val workflowId: String,
    val workflowStatus: String,
    val currentStepId: String,
    val stepUpdates: List<Map<String, Any?>>?,
    val artifactsPatch: Map<String, Any?>?,
    val context: CliRuntimeContext,
  )

  fun update(args: UpdateArgs): Map<String, Any?> {
    val runtime = component(args.context, args.dbPath)
    val service = runtime.workflowService
    val result =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = args.workflowId,
          workflowStatus = args.workflowStatus,
          currentStepId = args.currentStepId,
          stepUpdates = args.stepUpdates?.let(WorkflowStepUpdates::from),
          artifactsPatch = args.artifactsPatch?.let(WorkflowArtifactPatch::from),
          planningResult =
            args.artifactsPatch?.get(WorkflowArtifactKeys.PLAN)
              ?.let(JsonCodec::anyToStringAnyMap)
              ?.let { DecompositionPlanningResult.fromWireMap(it, "test.artifacts_patch.plan") },
        ),
      )
    return assertIs<WorkflowUpdateResult.Ok>(result).toPayload(runtime.workflowArtifactWireEncoder)
  }

  fun get(
    dbPath: Path,
    workflowId: String,
    context: CliRuntimeContext,
  ): Map<String, Any?> {
    val runtime = component(context, dbPath)
    val service = runtime.workflowService
    val result = service.get(WorkflowFamilyKind.TASK_RUNTIME, workflowId)
    return assertIs<WorkflowGetResult.Ok>(result).toCliMap(runtime.workflowArtifactWireEncoder)
  }

  fun continueByIssueKey(
    dbPath: Path,
    issueKey: String,
    subtaskId: Int?,
    context: CliRuntimeContext,
  ): Map<String, Any?> {
    val runtime = component(context, dbPath)
    val service = runtime.workflowService
    val result =
      service.continueWorkflow(
        kind = WorkflowFamilyKind.TASK_RUNTIME,
        workflowId = issueKey,
        subtaskId = subtaskId,
      )
    val workflowId =
      when (result) {
        is WorkflowContinueResult.Standard -> result.view.resume.snapshot.workflowId
        is WorkflowContinueResult.DecompositionBlockedSubtask -> result.workflowId
        is WorkflowContinueResult.DecompositionBlockedBranchStart -> result.workflowId
        is WorkflowContinueResult.DecompositionDone -> result.workflowId
        is WorkflowContinueResult.DecompositionSubtaskOutcome -> result.workflowId
        is WorkflowContinueResult.DecompositionBlockedGit -> result.workflowId
        is WorkflowContinueResult.DecompositionStandard -> result.view.resume.snapshot.workflowId
        is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow ->
          error("Missing decomposition workflow for subtask ${result.subtaskId}.")
        is WorkflowContinueResult.UnknownWorkflow ->
          error("Unknown workflow_id '${result.workflowId}'.")
        is WorkflowContinueResult.Error -> error(result.error)
      }
    return mapOf("workflow_id" to workflowId)
  }

  fun parseStepUpdates(rawJson: String): List<Map<String, Any?>> =
    JsonCodec.parseJsonArrayStrict(rawJson).map { value ->
      requireNotNull(JsonCodec.anyToStringAnyMap(value))
    }

  fun parseArtifactsPatch(rawJson: String): Map<String, Any?> =
    requireNotNull(
      JsonCodec.parseObjectOrNull(rawJson)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap),
    )

  private fun component(
    context: CliRuntimeContext,
    dbPath: Path,
  ): RuntimeComponent = RuntimeComponent::class.create(context.toRuntimeContext(dbPathOverride = dbPath.toString()))
}
