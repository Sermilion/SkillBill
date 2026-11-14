package skillbill.cli.kernel.plan

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.application.config.ConfigResolutionService
import skillbill.cli.model.CliRunInputs
import skillbill.config.model.CompactionSettings
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeAgentAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.goalrunner.plan.StandalonePlanLaunch
import skillbill.engine.goalrunner.plan.StandalonePlanResult
import skillbill.engine.goalrunner.plan.StandalonePlanRun
import skillbill.idestatus.model.IdeStatusLaunchScope
import java.nio.file.Path
import kotlin.time.Duration

@Inject
class StandalonePlanLauncher(
  private val planRun: StandalonePlanRun,
  private val configResolution: ConfigResolutionService,
  private val inputs: CliRunInputs,
) {
  fun issueKeyOf(intake: String): String = planRun.issueKeyOf(intake)

  fun retainedGoalPlanningWorkflowId(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): String? = planRun.retainedGoalPlanningWorkflowId(issueKey, intake, repoRoot)

  fun retainedGoalRoutingProviders(
    workflowId: String,
    invokedAgentId: String,
    agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  ): Map<String, Set<String>>? = planRun.retainedGoalRoutingProviders(workflowId, invokedAgentId, agentAssignment)

  fun prepareGoalCheckout(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): StandalonePlanResult.Refused? = planRun.prepareGoalCheckout(issueKey, intake, repoRoot)

  fun run(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    invokedAgentId: String,
    options: StandalonePlanOptions = StandalonePlanOptions(),
  ): StandalonePlanResult {
    val selectedWorkflowId =
      options.selectedWorkflowId ?: if (options.launchScope == IdeStatusLaunchScope.GOAL) {
        planRun.retainedGoalPlanningWorkflowId(issueKey, intake, repoRoot)
      } else {
        null
      }
    val hasPreparedMatrix = options.modelAssignment?.matrix != null || options.agentAssignment.matrix != null
    val configuration =
      if (selectedWorkflowId != null || options.routingContextPrepared || hasPreparedMatrix) {
        null
      } else {
        configResolution.resolveRunConfiguration()
      }
    val matrix = options.modelAssignment?.matrix ?: options.agentAssignment.matrix ?: configuration?.executionMatrix
    val compactionSettings =
      if (selectedWorkflowId != null && options.compactionSettings == CompactionSettings.DEFAULT) {
        configResolution.resolveCompactionSettings()
      } else {
        configuration?.compactionSettings ?: options.compactionSettings
      }
    return planRun.run(
      issueKey,
      intake,
      repoRoot,
      StandalonePlanLaunch(invokedAgentId, options.launchScope, retainedWorkflowId = selectedWorkflowId),
    ) { specPath ->
      FeatureTaskRuntimeRunInput(
        issueKey = issueKey,
        specPath = specPath,
        repoRoot = repoRoot,
        explicitWorkflowId = null,
        invokedAgentId = invokedAgentId,
        agentAssignment = options.agentAssignment.copy(matrix = matrix),
        modelAssignment =
          options.modelAssignment?.copy(matrix = matrix) ?: FeatureTaskRuntimeModelAssignment(matrix = matrix),
        compactionSettings = compactionSettings,
        environment = inputs.environment,
        timeout = options.timeout,
        requestedCodeReviewMode = null,
        goalContinuation = null,
        operatorDecision = null,
        agentAddonSelection = options.agentAddonSelection,
        eventSink = options.eventSink,
        routingContextPrepared = true,
      )
    }
  }
}

data class StandalonePlanOptions(
  val timeout: Duration? = null,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  val modelAssignment: FeatureTaskRuntimeModelAssignment? = null,
  val compactionSettings: CompactionSettings = CompactionSettings.DEFAULT,
  val routingContextPrepared: Boolean = false,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val eventSink: FeatureTaskRuntimeRunEventSink = FeatureTaskRuntimeRunEventSink.NONE,
  val launchScope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
  val selectedWorkflowId: String? = null,
)

fun StandalonePlanResult.planReportText(
  issueKey: String,
  incidentLines: List<String> = emptyList(),
): String =
  (
    when (this) {
      is StandalonePlanResult.Completed -> {
        (
          listOf("Parent spec: $parentSpecPath", "Manifest: $decompositionManifestPath") +
            subtaskSpecPaths.map { path -> "Subtask spec: $path" } +
            listOf("Plan workflow ID: $workflowId", "Run the goal with: sona $issueKey")
        ).joinToString("\n")
      }

      is StandalonePlanResult.Blocked -> {
        "Plan for $issueKey blocked: $reason\nPlan workflow ID: $workflowId\n" +
          "Resume with: sona phase plan $issueKey (or sona $issueKey)"
      }

      is StandalonePlanResult.Refused -> {
        listOfNotNull(reason, workflowId?.let { "Plan workflow ID: $it" }).joinToString("\n")
      }
    } + incidentLines.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n").orEmpty()
  )
