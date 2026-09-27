package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.time.Clock

/**
 * What one step call of the attempt loop reads: the per-call facts and the run's [PhaseRunState]. Every run-state
 * read and write goes through [runState]; the collaborators and gates come from the run state.
 */
internal interface PhaseAttemptEnvironment {
  /** The per-call facts of the run. */
  val request: FeatureTaskRuntimeRunFacts

  /** The run's state. */
  val runState: PhaseRunState

  /** The validator step outputs are decoded with. */
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator
    get() = runState.collaborators.outputValidator

  /** The gates a step consults. */
  val phaseGates: FeatureTaskRuntimePhaseGates
    get() = runState.phaseGates

  /** The clock evidence is stamped with. */
  val clock: Clock
    get() = runState.collaborators.clock

  /** The diagnostics best-effort failures are recorded to. */
  val diagnostics: RuntimeDiagnostics
    get() = runState.collaborators.diagnostics

  /** The in-memory progress of the run. */
  val state: FeatureTaskRuntimeRunState
    get() = runState.progress

  /** The run's session. */
  val session: FeatureTaskRuntimeRunLoopSession
    get() = runState.session

  /** The step lifecycle events and telemetry of the run. */
  val observability: FeatureTaskRuntimeRunObservability
    get() = runState.telemetry

  /** The step records of the run. */
  val recorder: PhaseRunRecords
    get() = runState.records

  /** The goal-continuation state of the run. */
  val goalContinuationRecorder: PhaseRunGoal
    get() = runState.goal

  /** The step settlements of the run. */
  val phaseSettlementService: PhaseRunSettlements
    get() = runState.settlements

  /** The subtask commit and checkpoint ref writes of the run. */
  val checkpoints: PhaseRunCheckpoints
    get() = runState.checkpoints

  /** The spec the run implements. */
  val specSource: SpecSource
    get() = runState.specSource

  /** The transitions the run traverses. */
  val transitions: FeatureTaskRuntimeTransitionDeclaration
    get() = runState.transitions

  /** The strategy selected for [stepId]. */
  fun strategyFor(stepId: String): PhaseStrategy = runState.strategyFor(stepId)

  /** The policy the strategy selected for [stepId] declares for it. */
  fun stepPolicy(stepId: String): PhaseStepPolicy = strategyFor(stepId).policyFor(stepId)

  /** Whether the selected strategy that owns [stepId] declares that the step extends the workflow-owned paths. */
  fun extendsOwnedInventory(stepId: String): Boolean =
    runState.selectedOwnerOf(stepId)?.policyFor(stepId)?.extendsOwnedInventory == true

  /** The steps of the definition no selected strategy owns. */
  fun unselectedStepIds(): Set<String> = runState.unselectedStepIds()
}

internal class PhaseAttemptScope(
  override val request: FeatureTaskRuntimeRunFacts,
  override val runState: PhaseRunState,
) : PhaseAttemptEnvironment
