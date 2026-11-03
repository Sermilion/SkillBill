package skillbill.engine.goalrunner.model

import java.nio.file.Path

public data class GoalRunnerExportRequest(
  public val issueKey: String,
  public val repoRoot: Path?,
  public val outputPath: Path,
)

public sealed interface GoalRunnerExportResult {
  public val issueKey: String

  public data class Exported(
    override val issueKey: String,
    public val featureBranch: String,
    public val commitSha: String,
    public val archivePath: Path,
    public val fileCount: Int,
    public val rowCounts: Map<String, Int>,
  ) : GoalRunnerExportResult

  public data class Refused(
    override val issueKey: String,
    public val reason: String,
  ) : GoalRunnerExportResult

  public data class Failed(
    override val issueKey: String,
    public val message: String,
  ) : GoalRunnerExportResult
}
