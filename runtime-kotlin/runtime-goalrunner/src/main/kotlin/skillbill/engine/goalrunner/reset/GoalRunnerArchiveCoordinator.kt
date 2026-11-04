package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalRunnerArchiveInspectResult
import skillbill.engine.goalrunner.model.GoalRunnerExportPreflightResult
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import java.nio.file.Path

@Inject
public class GoalRunnerArchiveCoordinator(
  private val exportOperations: GoalRunnerArchiveExportOperations,
  private val importOperations: GoalRunnerArchiveImportOperations,
) {
  public fun import(
    request: GoalRunnerImportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerImportResult = importOperations.import(request, expectedObservation)

  public fun inspect(archivePath: Path): GoalRunnerArchiveInspectResult = importOperations.inspect(archivePath)

  public fun preflightExport(
    issueKey: String,
    repoRoot: Path,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerExportPreflightResult = exportOperations.preflight(issueKey, repoRoot, expectedObservation)

  public fun export(
    request: GoalRunnerExportRequest,
    expectedObservation: DatabaseFileObservation? = null,
  ): GoalRunnerExportResult = exportOperations.export(request, expectedObservation)
}
