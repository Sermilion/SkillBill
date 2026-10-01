package skillbill.engine.goalrunner.planning.sweep

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRefreshLiveness
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import java.time.Clock

@Inject
data class GoalPlanningSweepCheckpointBoundaries(
  val checkpoint: GoalPlanningPreparationCheckpoint,
  val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  val manifestFileStore: DecompositionManifestStore,
  val contextDiscovery: GoalPlanningContextDiscovery,
)

@Inject
data class GoalPlanningSweepLaunchBoundaries(
  val manifestStore: GoalRunnerManifestStore,
  val planningAttemptRecorder: GoalPlanningAttemptRecorder,
  val planningRejectionRecorder: GoalPlanningRejectionRecorder,
  val timingPort: RuntimeTimingPort,
  val burstSchedule: GoalPlanningBurstSchedule,
  val refreshLiveness: GoalPlanningRefreshLiveness,
  val phaseStrategies: PhaseStrategyLookup,
  val clock: Clock,
  val diagnostics: RuntimeDiagnostics,
  val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
)
