package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.remedies.goalPlanningPreparationStateReadStopReason
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

fun preSweepStopped(
  request: GoalRunnerRunRequest,
  reason: String,
  currentSubtaskId: Int = 0,
): GoalPlanningSweepOutcome.Stopped =
  GoalPlanningSweepOutcome.Stopped(
    issueKey = request.issueKey,
    currentSubtaskId = currentSubtaskId,
    reason = GoalRunnerStopReason.BLOCKED,
    blockedReason = reason,
    lastResumableStep = GoalPlanningSweepConstants.PHASE_PREPLAN,
  )

fun canonicalRepository(
  repoRoot: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path =
  repositoryEnclosingRootPort.canonicalPath(
    repositoryEnclosingRootPort.enclosingRepositoryRoot(repoRoot),
  )

fun planningProgressMessage(
  phaseId: String,
  subtask: DecompositionSubtask?,
): String =
  if (phaseId == GoalPlanningSweepConstants.PHASE_PREPLAN) {
    "skill-bill: goal planning - parent goal shared preplan\n"
  } else {
    "skill-bill: goal planning - subtask ${requireNotNull(subtask).id} plan\n"
  }

fun sharedContextReason(error: Throwable): String =
  "Goal planning shared context could not be gathered: ${error.message.orEmpty()}"

fun projectionRejectedReason(
  phaseId: String,
  error: Throwable,
): String =
  "Goal planning phase '$phaseId' rejected a declared bounded projection at the launch seam: " +
    "${error.message.orEmpty()}. Preserve the saved record. Use a runtime that supports its contract or " +
    "restore or repair the identified record and its digest before resuming."

fun preparationStateReadReason(
  error: Throwable,
  issueKey: String,
  subtaskId: Int,
): String = goalPlanningPreparationStateReadStopReason(error, issueKey, subtaskId)

internal fun stopped(
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  blockedReason: String,
  lastResumableStep: String = GoalPlanningSweepConstants.PHASE_PREPLAN,
  reason: GoalRunnerStopReason = GoalRunnerStopReason.BLOCKED,
): GoalPlanningSweepOutcome.Stopped =
  GoalPlanningSweepOutcome.Stopped(
    issueKey = shared.issueKey,
    currentSubtaskId = subtaskId,
    reason = reason,
    blockedReason = blockedReason,
    lastResumableStep = lastResumableStep,
  )

fun noSuchSubtaskReason(subtaskId: Int): String =
  "Goal planning selected subtask '$subtaskId' which is not present in the accepted decomposition."

fun unresolvedSpecReason(subtask: DecompositionSubtask): String =
  "Goal planning subtask '${subtask.id}' governed spec path '${subtask.specPath}' could not be resolved " +
    "inside the repository."

fun invariantReadReason(
  subtask: DecompositionSubtask,
  error: Throwable,
): String = "Goal planning subtask '${subtask.id}' run-invariants could not be read: ${error.message.orEmpty()}"
