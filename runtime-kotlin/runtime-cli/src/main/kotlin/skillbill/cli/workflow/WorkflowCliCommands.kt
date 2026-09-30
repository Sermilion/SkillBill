package skillbill.cli.workflow

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowLatestResult.Error
import skillbill.application.workflow.model.WorkflowLatestResult.Ok
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.service.WorkflowService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.payload.toPayload
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.workflow.payload.WorkflowArtifactKeys
import skillbill.error.core.MalformedJsonTextError
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.FeatureTaskRouteScope

private val VERIFY_KIND = WorkflowFamilyKind.VERIFY

@Inject
class WorkflowTopLevelCommands(
  verifyCommands: VerifyWorkflowCommands,
) {
  val verifyWorkflowCommand: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "verify-workflow",
      "Inspect or resume durable bill-feature-verify workflow runs.",
    ) {}
      .subcommands(verifyCommands.commands)

  val commands: List<CliktCommand> = listOf(verifyWorkflowCommand)
}

@Inject
class VerifyWorkflowCommands(
  open: VerifyWorkflowOpenCommand,
  update: VerifyWorkflowUpdateCommand,
  show: VerifyWorkflowShowCommand,
  get: VerifyWorkflowGetCommand,
  list: VerifyWorkflowListCommand,
  latest: VerifyWorkflowLatestCommand,
  resume: VerifyWorkflowResumeCommand,
  continueCommand: VerifyWorkflowContinueCommand,
) {
  val commands: List<CliktCommand> = listOf(open, update, show, get, list, latest, resume, continueCommand)
}

@Inject
class VerifyWorkflowOpenCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("open", "Open durable workflow state.") {
  private val sessionId by option("--session-id", help = "Optional workflow telemetry session id.").default("")
  private val currentStepId by option("--current-step-id", help = "Initial workflow step id.")
  private val issueKey by option("--issue-key", help = "Optional normalized issue key for work inventory.")
  private val format by formatOption()

  override fun run() {
    val opened =
      service.open(
        WorkflowServiceOpenArgs(
          kind = VERIFY_KIND,
          sessionId = sessionId,
          currentStepId = currentStepId,
          issueKey = issueKey,
          repositoryIdentity = null,
          governedSpecPath = null,
          routeScope = FeatureTaskRouteScope.STANDALONE,
        ),
      )
    val payload = opened.toCliMap()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowUpdateCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("update", "Update durable workflow state and return a compact acknowledgement.") {
  private val workflowId by argument(help = "Workflow id to update.")
  private val workflowStatus by option("--workflow-status", help = "Next workflow status.").required()
  private val currentStepId by option("--current-step-id", help = "Optional current step id.").default("")
  private val stepUpdates by option("--step-updates", help = "JSON array of step updates.")
  private val artifactsPatch by option("--artifacts-patch", help = "JSON object of artifacts to merge.")
  private val sessionId by option("--session-id", help = "Optional replacement session id.").default("")
  private val format by formatOption()

  override fun run() {
    val parsedArtifactsPatch = artifactsPatch?.let(::parseArtifactsPatch)
    val request =
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = workflowStatus,
        currentStepId = currentStepId,
        stepUpdates = stepUpdates?.let(::parseStepUpdatesStrict)?.let(WorkflowStepUpdates::from),
        artifactsPatch = parsedArtifactsPatch?.let(WorkflowArtifactPatch::from),
        planningResult =
          parsedArtifactsPatch?.get(WorkflowArtifactKeys.PLAN)
            ?.let(JsonCodec::anyToStringAnyMap)
            ?.let { DecompositionPlanningResult.fromWireMap(it, "cli.artifacts_patch.plan") },
        sessionId = sessionId,
      )
    val payload = service.update(VERIFY_KIND, request).toPayload()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowShowCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("show", service, state, VERIFY_KIND)

@Inject
class VerifyWorkflowGetCommand(
  service: WorkflowService,
  state: CliRunState,
) : WorkflowGetCommand("get", service, state, VERIFY_KIND)

open class WorkflowGetCommand(
  name: String,
  private val service: WorkflowService,
  private val state: CliRunState,
  private val kind: WorkflowFamilyKind,
) : DocumentedCliCommand(name, "Fetch read-only full durable workflow state.") {
  private val workflowId by argument(help = "Workflow id to inspect.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, kind)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.get(kind, requireNotNull(resolution.workflowId)).toCliMap()
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowListCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List recent persisted workflow runs.") {
  private val limit by option("--limit", help = "Maximum number of workflows to return.").int()
    .default(DEFAULT_WORKFLOW_LIST_LIMIT)
  private val format by formatOption()

  override fun run() {
    val payload = service.list(VERIFY_KIND, limit).toCliMap()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowLatestCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("latest", "Fetch the most recently updated workflow run.") {
  private val format by formatOption()

  override fun run() {
    val payload = service.latest(VERIFY_KIND).toCliMap()
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowResumeCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("resume", "Summarize how to resume or recover a workflow run.") {
  private val workflowId by argument(help = "Workflow id to resume or recover.").optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, VERIFY_KIND)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.resume(VERIFY_KIND, requireNotNull(resolution.workflowId)).toCliMap()
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

@Inject
class VerifyWorkflowContinueCommand(
  private val service: WorkflowService,
  private val state: CliRunState,
) : DocumentedCliCommand("continue", "Activate a resumable workflow and emit a recovered continuation brief.") {
  private val workflowId by argument(
    help = "Workflow id to continue, or an issue key for a decomposed feature parent.",
  ).optional()
  private val latest by option("--latest", help = "Resolve the most recently updated workflow.").flag(default = false)
  private val format by formatOption()

  override fun run() {
    val resolution = resolveWorkflowId(workflowId, latest, service, VERIFY_KIND)
    val payload =
      if (resolution.errorPayload != null) {
        resolution.errorPayload
      } else {
        service.continueWorkflow(
          VERIFY_KIND,
          requireNotNull(resolution.workflowId),
        ).toCliMap()
      }
    state.complete(payload, format, exitCode = payload.exitCode())
  }
}

private fun parseStepUpdatesStrict(rawValue: String): List<Map<String, Any?>> {
  val parsed =
    try {
      JsonCodec.parseValue(rawValue)
    } catch (_: MalformedJsonTextError) {
      throw UsageError("--step-updates must be a JSON array of objects.")
    }
  val updates =
    parsed as? List<*>
      ?: throw UsageError("--step-updates must be a JSON array of objects.")
  return updates.mapIndexed { index, value ->
    JsonCodec.anyToStringAnyMap(value) ?: invalidStepUpdate(index)
  }
}

private fun invalidStepUpdate(index: Int): Nothing = throw UsageError("--step-updates[$index] must be an object.")

private fun parseArtifactsPatch(rawValue: String): Map<String, Any?> =
  JsonCodec.parseObjectOrNull(rawValue)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?: run {
      require(false) { "artifacts_patch must be an object." }
      emptyMap()
    }

private fun Map<String, Any?>.exitCode(): Int = if (this[SharedPayloadKeys.STATUS] == "error") 1 else 0

private fun resolveWorkflowId(
  workflowId: String?,
  latest: Boolean,
  service: WorkflowService,
  kind: WorkflowFamilyKind,
): WorkflowIdResolution {
  workflowId?.let { return WorkflowIdResolution(workflowId = it) }
  require(latest) { "Provide a workflow_id or pass --latest." }
  return when (val latestResult = service.latest(kind)) {
    is Ok ->
      WorkflowIdResolution(workflowId = latestResult.summary.workflowId)
    is Error ->
      WorkflowIdResolution(workflowId = null, errorPayload = latestResult.toCliMap())
  }
}

private const val DEFAULT_WORKFLOW_LIST_LIMIT: Int = 20

private data class WorkflowIdResolution(
  val workflowId: String?,
  val errorPayload: Map<String, Any?>? = null,
)
