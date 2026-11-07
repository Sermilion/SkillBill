package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityBuildSourceProof
import skillbill.engine.goalrunner.persist.quality.GoalQualityPhaseProgressWrite
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityQaSourceWitness
import skillbill.goalrunner.model.quality.acceptedQaSourceWitness
import skillbill.goalrunner.model.quality.nestedQaSourceWitness
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
        modelAssignment = FeatureTaskRuntimeModelAssignment(inputs.modelDirectives, inputs.modelMatrix),
        qaSelection = inputs.qaSelection,
        qaDevice = request.qaDevice.takeIf { definition == SkeletonDefinition.GOAL_QA },
        acceptanceCriteria = inputs.acceptanceCriteria,
        eventSink =
          FeatureTaskRuntimeRunEventSink { event ->
            recordGoalQaEvent(outcomeStore, clock, state, request, event)
          },
        activityOutput = { stepName, activity ->
          recordGoalQaActivity(
            outcomeStore,
            clock,
            state,
            request,
            GoalQaActivity(inputs.effectiveAgentId, stepName, activity),
          )
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
    val execution = phase.execution ?: return base
    val definition = phase.definition
    val plan = acceptedPlan(phase)
    val progress =
      outcomeStore.goalQualityPhaseProgress(
        execution.parentWorkflowId,
        execution.invocationId,
        definition.id,
        plan,
      )
    var latestProgress = progress
    val proof =
      if (definition == SkeletonDefinition.GOAL_QA) {
        initialBuildSourceProof(
          execution,
          progress,
          state,
          request,
        )
      } else {
        null
      }
    val sourceFingerprint = { currentContentFingerprint(gitOperations, state, request) }
    return base.copy(
      goalQualityPhaseProgress = progress,
      acceptedGoalQualityQaSourceWitness =
        if (definition == SkeletonDefinition.GOAL_QA) {
          { next ->
            val admissionWitness =
              proof?.acceptedQaSourceWitness
                ?.takeIf { it.nestedProgressKey != null && it !in next.acceptedQaSourceWitnesses }
            admissionWitness ?: observeQaSource(next, latestProgress, execution, state, request)
          }
        } else {
          null
        },
      goalQualityValidationOccurrence = execution.validationOccurrence,
      persistGoalQualityPhaseProgress = { next, terminalFingerprint ->
        val accepted = persistProgress(phase, next, terminalFingerprint, proof)
        if (accepted != null) latestProgress = accepted
        accepted != null
      },
      terminalGoalQualityContentFingerprint =
        sourceFingerprint.takeIf {
          definition in setOf(SkeletonDefinition.GOAL_BUILD, SkeletonDefinition.VALIDATION)
        },
      acceptedGoalQualitySourceFingerprint = sourceFingerprint,
      latestGoalQualityPhaseProgress = { latestProgress },
    )
  }

  private fun acceptedPlan(phase: GoalQualityPhaseInput): String {
    val plans = requireNotNull(phase.execution?.executionPlans)
    return when (phase.definition) {
      SkeletonDefinition.GOAL_BUILD -> plans.build
      SkeletonDefinition.GOAL_QA -> plans.qa
      else -> plans.validation
    }
  }

  private fun initialBuildSourceProof(
    execution: GoalQualityExecution,
    progress: GoalQualityPhaseProgress,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQualityBuildSourceProof? {
    val buildProgress = requireNotNull(execution.progress).build
    val observation = buildProgress.acceptedSourceObservations.lastOrNull() ?: return null
    val currentSource = currentContentFingerprint(gitOperations, state, request)
    return GoalQualityBuildSourceProof(
      parentWorkflowId = execution.parentWorkflowId,
      invocationId = execution.invocationId,
      acceptedPlan = requireNotNull(execution.executionPlans).build,
      progressRevision = buildProgress.revision,
      phaseId = observation.phaseId,
      attempt = observation.attempt,
      terminalSequenceNumber = observation.completionSequenceNumber,
      acceptedContentFingerprint = observation.contentFingerprint,
      observedCurrentContentFingerprint = currentSource,
      acceptedQaSourceWitness =
        execution.copy(progress = requireNotNull(execution.progress).copy(qa = progress))
          .acceptedQaSourceWitness(currentSource, currentRepositoryCheckpoint(gitOperations, request)),
    )
  }

  private fun observeQaSource(
    next: GoalQualityPhaseProgress,
    previous: GoalQualityPhaseProgress?,
    execution: GoalQualityExecution,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQualityQaSourceWitness? {
    val gate = next.buildGateProgress
    val priorGate = previous?.buildGateProgress
    val newRepair =
      gate?.acceptedRepairOutputs?.lastOrNull()?.let {
        it !in priorGate?.acceptedRepairOutputs.orEmpty()
      } == true
    val newRun = gate?.gateRuns?.lastOrNull()?.let { it != priorGate?.gateRuns?.lastOrNull() } == true
    if (!newRepair && !newRun) return null
    return next.nestedQaSourceWitness(
      requireNotNull(execution.executionPlans).qa,
      "${execution.parentWorkflowId}:${execution.invocationId}",
      currentContentFingerprint(gitOperations, state, request),
      currentRepositoryCheckpoint(gitOperations, request),
      allowPendingRepair = newRepair,
    )?.takeUnless { candidate ->
      next.acceptedQaSourceWitnesses.any { it.copy(progressRevision = candidate.progressRevision) == candidate }
    }
  }

  private fun persistProgress(
    phase: GoalQualityPhaseInput,
    next: GoalQualityPhaseProgress,
    terminalFingerprint: String?,
    proof: GoalQualityBuildSourceProof?,
  ): GoalQualityPhaseProgress? {
    val execution = requireNotNull(phase.execution)
    val definition = phase.definition
    val plan = acceptedPlan(phase)
    val accepted =
      outcomeStore.persistGoalQualityPhaseProgress(
        GoalQualityPhaseProgressWrite(
          execution.parentWorkflowId,
          execution.invocationId,
          definition.id,
          plan,
          next,
          finalValidationContentFingerprint =
            terminalFingerprint.takeIf { definition == SkeletonDefinition.VALIDATION },
          preQaBuildContentFingerprint = terminalFingerprint.takeIf { definition == SkeletonDefinition.GOAL_BUILD },
          preQaBuildSourceProof = proof,
        ),
      )
    if (!accepted) return null
    return if (next.terminalCompleted || next.terminalBlockedStep != null) {
      outcomeStore.goalQualityPhaseProgress(execution.parentWorkflowId, execution.invocationId, definition.id, plan)
    } else {
      next
    }
  }
}
