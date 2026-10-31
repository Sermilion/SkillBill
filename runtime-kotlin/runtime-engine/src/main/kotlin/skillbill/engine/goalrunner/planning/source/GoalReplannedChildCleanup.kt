package skillbill.engine.goalrunner.planning.source

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

@Inject
public class GoalReplannedChildCleanup(
  private val manifestStore: GoalRunnerManifestStore,
) {
  public fun clear(
    state: GoalRunnerManifestState,
    replannedSubtaskIds: Set<Int>,
  ): GoalRunnerManifestState {
    val staleIds =
      state.manifest.subtasks
        .filter { it.id in replannedSubtaskIds && !it.workflowId.isNullOrBlank() }
        .filter { it.status.decompositionStatus() !in SETTLED_STATUSES }
        .map { it.id }
    return if (staleIds.isEmpty()) state else manifestStore.clearReplannedChildWorkflows(state, staleIds)
  }

  private companion object {
    val SETTLED_STATUSES = setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
  }
}
