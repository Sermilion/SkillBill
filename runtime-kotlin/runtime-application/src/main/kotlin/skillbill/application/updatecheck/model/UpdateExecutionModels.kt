package skillbill.application.updatecheck.model

import java.nio.file.Path

enum class UpdateRunStatus {
  COMPLETED,
  FAILED,
  DRY_RUN,
  SKIPPED,
  CHECK_FAILED,
  DOWNLOAD_FAILED,

  ;

  val wireValue: String
    get() =
      when (this) {
        COMPLETED -> "completed"
        FAILED,
        DOWNLOAD_FAILED,
        -> "failed"
        DRY_RUN -> "dry_run"
        SKIPPED -> "skipped"
        CHECK_FAILED -> "check_failed"
      }
}

data class UpdateRunRequest(
  val releaseTag: String?,
  val clean: Boolean,
  val dryRun: Boolean,
  val userHome: Path,
  val environment: Map<String, String>,
  val includePrereleases: Boolean = false,
)

data class UpdateRunPlan(
  val command: String,
  val installerArgs: List<String>,
  val scriptUrl: String,
)

data class UpdateRunResult(
  val status: UpdateRunStatus,
  val exitCode: Int,
  val plan: UpdateRunPlan,
  val installerOutput: String? = null,
  val updateCheck: UpdateCheckResult? = null,
  val reason: String? = null,
)

internal const val INSTALL_SCRIPT_URL: String = "https://raw.githubusercontent.com/Sermilion/skill-bill/main/install.sh"
