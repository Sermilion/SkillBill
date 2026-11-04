package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.ports.filesystem.model.DatabaseFileObservation

@Inject
public class GoalRunnerArchiveCoordinator(
  private val exportOperations: GoalRunnerArchiveExportOperations,
  private val importOperations: GoalRunnerArchiveImportOperations,
) {
  public fun import(request: GoalRunnerImportRequest): GoalRunnerImportResult = importOperations.import(request)

  public fun export(
    request: GoalRunnerExportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerExportResult = exportOperations.export(request, expectedObservation)
}
