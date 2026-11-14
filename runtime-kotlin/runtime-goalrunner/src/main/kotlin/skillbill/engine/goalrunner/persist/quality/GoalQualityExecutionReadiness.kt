package skillbill.engine.goalrunner.persist.quality

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.persist.GoalRunnerQualityExecutionStore
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode

@Inject
public class GoalQualityExecutionReadiness(
  private val database: DatabaseSessionFactory,
  private val qualityExecution: GoalRunnerQualityExecutionStore,
) {
  private var preparedObservation: DatabaseFileObservation? = null

  @Synchronized
  public fun prepareExisting() {
    val observation = database.readIfPresent { it.databaseFileObservation } ?: return
    if (preparedObservation == observation) return
    preparedObservation = null
    val workflowIds =
      database.readIfPresent { unitOfWork ->
        unitOfWork.workflowStates
          .listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE)
          .filter { DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.contains(it.typedArtifacts) }
          .map { it.workflowId }
      }.orEmpty()
    workflowIds.forEach(qualityExecution::prepareGoalQualityExecution)
    when (database.transactionFenced(observation) { Unit }) {
      FencedTransactionResult.ChangedTarget -> Unit
      is FencedTransactionResult.Committed -> preparedObservation = observation
    }
  }
}
