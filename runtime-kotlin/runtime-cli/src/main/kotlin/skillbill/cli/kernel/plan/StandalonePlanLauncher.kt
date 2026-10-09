package skillbill.cli.kernel.plan

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.model.CliRunInputs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.goalrunner.plan.StandalonePlanResult
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import java.nio.file.Path
import kotlin.time.Duration

@Inject
class StandalonePlanLauncher(
  private val planRun: StandalonePlanRun,
  private val configResolution: ConfigResolutionService,
  private val inputs: CliRunInputs,
) {
  fun issueKeyOf(intake: String): String = planRun.issueKeyOf(intake)

  fun incompletePlanWorkflowId(
    issueKey: String,
    repoRoot: Path,
  ): String? = planRun.incompletePlanWorkflowId(issueKey, repoRoot)

  fun run(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    invokedAgentId: String,
    options: StandalonePlanOptions = StandalonePlanOptions(),
  ): StandalonePlanResult =
    planRun.run(issueKey, intake, repoRoot) { specPath ->
      FeatureTaskRuntimeRunInput(
        issueKey = issueKey,
        specPath = specPath,
        repoRoot = repoRoot,
        explicitWorkflowId = null,
        invokedAgentId = invokedAgentId,
        agentAssignment = FeatureTaskRuntimeAgentAssignment(),
        modelAssignment = FeatureTaskRuntimeModelAssignment(matrix = configResolution.resolveExecutionMatrix()),
        compactionSettings = configResolution.resolveCompactionSettings(),
        environment = inputs.environment,
        timeout = options.timeout,
        requestedCodeReviewMode = null,
        goalContinuation = null,
        operatorDecision = null,
        agentAddonSelection = options.agentAddonSelection,
        eventSink = options.eventSink,
      )
    }
}

data class StandalonePlanOptions(
  val timeout: Duration? = null,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
)

fun StandalonePlanResult.planReportText(issueKey: String): String =
  when (this) {
    is StandalonePlanResult.Completed ->
      (
        listOf("Parent spec: $parentSpecPath", "Manifest: $decompositionManifestPath") +
          subtaskSpecPaths.map { path -> "Subtask spec: $path" } +
          listOf("Plan workflow ID: $workflowId", "Run the goal with: skill-bill $issueKey")
      ).joinToString("\n")
    is StandalonePlanResult.Blocked ->
      "Plan for $issueKey blocked: $reason\nPlan workflow ID: $workflowId\n" +
        "Resume with: skill-bill phase plan $issueKey (or skill-bill $issueKey)"
    is StandalonePlanResult.Refused ->
      listOfNotNull(reason, workflowId?.let { "Plan workflow ID: $it" }).joinToString("\n")
  }
