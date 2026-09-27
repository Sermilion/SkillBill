package skillbill.engine.featuretask.runloop.settlement

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.model.ValidationGateResolution

object FeatureTaskRuntimeRunLoopValidationScope {
  internal fun validationChangedPaths(
    phaseGates: FeatureTaskRuntimePhaseGates,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal,
    session: FeatureTaskRuntimeRunLoopSession,
    run: PhaseRun,
  ): List<String>? =
    with(FeatureTaskRuntimeRunLoopOutputVerification) {
      resolveRepositoryCheckpoint(
        RepositoryCheckpointResolutionArgs(
          recorder = recorder,
          goalContinuationRecorder = goalContinuationRecorder,
          phaseGates = phaseGates,
          session = session,
          run = run,
        ),
      )
        ?.workingTreeOwnedPaths
        ?.distinct()
        ?.sorted()
    }

  internal fun packBuildCommand(
    phaseGates: FeatureTaskRuntimePhaseGates,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal,
    session: FeatureTaskRuntimeRunLoopSession,
    run: PhaseRun,
  ): String? {
    val validationChangedPaths =
      validationChangedPaths(
        phaseGates,
        recorder,
        goalContinuationRecorder,
        session,
        run,
      )
    return when (
      val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths.orEmpty())
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }
}
