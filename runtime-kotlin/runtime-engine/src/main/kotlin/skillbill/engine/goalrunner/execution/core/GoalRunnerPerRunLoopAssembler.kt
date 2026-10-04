package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep

@Inject
class GoalRunnerPerRunLoopAssembler(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalPlanningSweep: GoalPlanningSweep,
  private val finalization: GoalRunnerFinalization,
  private val selectedSubtaskLoop: GoalRunnerSelectedSubtaskLoop,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val progressReader: GoalRunnerProgressReader,
) {
  internal fun assemble(): GoalRunnerGoalLoop =
    GoalRunnerGoalLoop(
      manifestStore,
      goalPlanningSweep,
      finalization,
      selectedSubtaskLoop,
      pauseBoundary,
      progressReader,
    )
}
