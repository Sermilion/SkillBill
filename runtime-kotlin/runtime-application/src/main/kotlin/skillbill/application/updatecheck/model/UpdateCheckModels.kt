package skillbill.application.updatecheck.model

const val RECOMMENDED_INSTALL_COMMAND: String =
  "sona update"

enum class UpdateCheckStatus(val wireName: String) {
  UP_TO_DATE("up_to_date"),
  UPDATE_AVAILABLE("update_available"),
  AHEAD_OF_RELEASE("ahead_of_release"),
  UNKNOWN("unknown"),
}

data class UpdateCheckResult(
  val status: UpdateCheckStatus,
  val installedVersion: String? = null,
  val latestVersion: String? = null,
  val releaseUrl: String? = null,
  val recommendedInstallCommand: String? = null,
  val reason: String? = null,
  val releaseNotes: String? = null,
)
