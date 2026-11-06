package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityPhaseProgressWrite
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Inject
public class GoalQualityPhaseRequests(
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val clock: Clock,
  private val activityStampWriter: AgentActivityStampWriter,
) {
  internal fun create(
    phase: GoalQualityPhaseInput,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    repairOutput: (String, String, Int, String) -> Unit,
  ): PhaseRunRequest {
    val (definition, inputs, qualityExecution) = phase
    val base =
      PhaseRunRequest(
        definitionId = definition.id,
        repoRoot = Path.of(inputs.repoRoot),
        invokedAgentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
        acceptedGoalQualityAgentId = qualityExecution?.governingInputs?.effectiveAgentId,
        intake = inputs.issueKey,
        timeout = inputs.timeoutMillis?.milliseconds,
        agentAddonSelection =
          HydratedAgentAddonSelection(
            inputs.addons.map { HydratedAgentAddonSelectionEntry(it.persisted, it.description, it.content) },
          ),
        modelAssignment = resumeModelAssignment(inputs, request),
        qaSelection = inputs.qaSelection,
        acceptanceCriteria = inputs.acceptanceCriteria,
        eventSink =
          FeatureTaskRuntimeRunEventSink { event ->
            recordGoalQaEvent(outcomeStore, clock, state, request, event)
          },
        activityOutput = { stepName, activity ->
          recordGoalQaActivity(outcomeStore, clock, state, request, stepName to activity)
        },
        activityStampSink = activityStampWriter.sink(state.parentWorkflowId, parentWorkflowId = null),
        recordedGoalQaOutcome =
          qualityExecution?.takeIf { definition == SkeletonDefinition.GOAL_QA }
            ?.let { outcomeStore.recordedGoalQaOutcome(qaOutcomeWorkflowId(state, it.invocationId)) },
        goalQualityInvocationId = qualityExecution?.invocationId,
        acceptedGoalRepairOutput = repairOutput,
      )
    return bindProgress(base, phase, state, request)
  }

  private fun bindProgress(
    base: PhaseRunRequest,
    phase: GoalQualityPhaseInput,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): PhaseRunRequest {
    val (definition, _, qualityExecution) = phase
    val acceptedPlan =
      qualityExecution?.executionPlans?.let { plans ->
        if (definition == SkeletonDefinition.GOAL_QA) plans.qa else plans.validation
      }
    val progress =
      if (qualityExecution == null || acceptedPlan == null) {
        null
      } else {
        outcomeStore.goalQualityPhaseProgress(
          state.parentWorkflowId,
          qualityExecution.invocationId,
          definition.id,
          acceptedPlan,
        )
      }
    var latestProgress = progress
    return base.copy(
      goalQualityPhaseProgress = progress,
      goalQualityValidationOccurrence = qualityExecution?.validationOccurrence ?: 0,
      persistGoalQualityPhaseProgress =
        if (qualityExecution == null || acceptedPlan == null) {
          null
        } else {
          { next, terminalFingerprint ->
            val accepted =
              outcomeStore.persistGoalQualityPhaseProgress(
                GoalQualityPhaseProgressWrite(
                  state.parentWorkflowId,
                  qualityExecution.invocationId,
                  definition.id,
                  acceptedPlan,
                  next,
                  terminalFingerprint,
                ),
              )
            if (accepted) latestProgress = next
            accepted
          }
        },
      terminalGoalQualityContentFingerprint =
        if (definition == SkeletonDefinition.VALIDATION && qualityExecution != null) {
          { currentContentFingerprint(gitOperations, state, request) }
        } else {
          null
        },
      latestGoalQualityPhaseProgress =
        if (qualityExecution == null || acceptedPlan == null) {
          null
        } else {
          { requireNotNull(latestProgress) }
        },
    )
  }
}
