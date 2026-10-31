package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.source.GoalPlanningSource

@Inject
public class GoalRunnerPerRunLoopAssembler(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalPlanningSource: GoalPlanningSource,
  private val reconciliation: GoalRunnerFinalReconciliation,
  private val finalization: GoalRunnerFinalization,
  private val selectedSubtaskLoop: GoalRunnerSelectedSubtaskLoop,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val progressReader: GoalRunnerProgressReader,
) {
  internal fun assemble(): GoalRunnerGoalLoop =
    GoalRunnerGoalLoop(
      manifestStore,
      goalPlanningSource,
      reconciliation,
      finalization,
      selectedSubtaskLoop,
      pauseBoundary,
      progressReader,
    )
}
