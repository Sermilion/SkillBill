package skillbill.engine.work

import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.work.control.WorkRunActions
import skillbill.engine.work.control.goalStartRefusal
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path

val GOAL_FINISH_PIPELINE_FOR_TEST: List<String> get() = GOAL_FINISH_PIPELINE

fun IdeStatusProjector.goalFinishPipelineForTest(
  repoRoot: Path,
  assessment: GoalRunnerAssessmentResult? = null,
): List<String> = goalFinishPipeline(repoRoot, assessment)

fun WorkRunActions.optionsForTest(
  item: WorkDetailItem,
  unit: UnitOfWork,
) = options(item, unit)

fun goalStartRefusalForTest(result: GoalPreflightResult): String? = goalStartRefusal(result)

fun latestPhaseExecutionsForTest(records: List<StandalonePhaseStatusRecord>): List<StandalonePhaseStatusRecord> =
  latestPhaseExecutions(records)

fun resolveExecutionReasonTextForTest(
  record: StandalonePhaseStatusRecord,
  unit: UnitOfWork,
): String? = resolveExecutionReason(record, unit).text

fun WorkRunActions.startGoalForTest(
  repoRoot: Path,
  issueKey: String,
  agentId: String?,
) = startGoal(repoRoot, issueKey, agentId)

fun WorkRunActions.launchForTest(
  target: WorkRunTarget,
  agentId: String?,
) = launch(target, agentId)
