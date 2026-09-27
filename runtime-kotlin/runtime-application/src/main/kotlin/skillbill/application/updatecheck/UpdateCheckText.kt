package skillbill.application.updatecheck

import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.model.UpdateCheckStatus

/** The text report `skill-bill update-check` and `skill-bill operation update-check` both print. */
fun UpdateCheckResult.toText(): String =
  buildString {
    when (status) {
      UpdateCheckStatus.UP_TO_DATE -> {
        appendLine("status: up_to_date")
        appendLine("installed_version: $installedVersion")
        appendLine("latest_version: $latestVersion")
      }
      UpdateCheckStatus.UPDATE_AVAILABLE -> {
        appendLine("status: update_available")
        appendLine("installed_version: $installedVersion")
        appendLine("latest_version: $latestVersion")
        appendLine("release_url: $releaseUrl")
        appendLine("recommended_install_command: $recommendedInstallCommand")
        releaseNotes?.let {
          appendLine()
          appendLine("what's new:")
          appendLine(it)
        }
      }
      UpdateCheckStatus.AHEAD_OF_RELEASE -> {
        appendLine("status: ahead_of_release")
        appendLine("installed_version: $installedVersion")
        appendLine("latest_version: $latestVersion")
        appendLine("release_url: $releaseUrl")
      }
      UpdateCheckStatus.UNKNOWN -> {
        appendLine("status: unknown")
        appendLine("reason: ${reason.orEmpty()}")
        installedVersion?.let { appendLine("installed_version: $it") }
      }
    }
  }
