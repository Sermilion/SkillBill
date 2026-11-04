package skillbill.engine.goalrunner.model

import java.nio.file.Path

public data class GoalRunnerImportRequest(
  public val archivePath: Path,
  public val repoRoot: Path? = null,
  public val expectedFingerprint: String? = null,
) {
  init {
    require(archivePath.toString().isNotBlank()) { "archivePath is required." }
  }
}

public sealed interface GoalRunnerImportResult {
  public val issueKey: String

  public data class Imported(
    override val issueKey: String,
    public val featureBranch: String,
    public val commitSha: String,
    public val archivePath: Path,
    public val rowCounts: Map<String, Int>,
  ) : GoalRunnerImportResult

  public data class Refused(
    override val issueKey: String,
    public val reason: String,
    public val featureBranch: String? = null,
    public val commitSha: String? = null,
  ) : GoalRunnerImportResult

  public data class Failed(
    override val issueKey: String,
    public val message: String,
  ) : GoalRunnerImportResult

  public data class Incomplete(
    override val issueKey: String,
    public val missingSpecPaths: List<String>,
    public val featureBranch: String,
    public val commitSha: String,
    public val archivePath: Path,
    public val retryHint: String,
  ) : GoalRunnerImportResult
}

public sealed interface GoalRunnerArchiveInspectResult {
  public data class Ready(
    public val fingerprint: String,
    public val issueKey: String,
    public val featureBranch: String,
    public val commitSha: String,
  ) : GoalRunnerArchiveInspectResult

  public data class Refused(public val reason: String) : GoalRunnerArchiveInspectResult

  public data class Failed(public val message: String) : GoalRunnerArchiveInspectResult
}
