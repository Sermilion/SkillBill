package skillbill.application.updatecheck

import me.tatarka.inject.annotations.Inject
import skillbill.application.updatecheck.model.INSTALL_SCRIPT_URL
import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.model.UpdateCheckStatus
import skillbill.application.updatecheck.model.UpdateRunPlan
import skillbill.application.updatecheck.model.UpdateRunRequest
import skillbill.application.updatecheck.model.UpdateRunResult
import skillbill.application.updatecheck.model.UpdateRunStatus
import skillbill.ports.process.InstallerProcessPort
import skillbill.ports.process.InstallerScriptFetchPort
import skillbill.ports.process.model.InstallerProcessRequest
import skillbill.ports.process.model.InstallerScriptFetchRequest
import skillbill.ports.process.model.InstallerScriptFetchResult
import java.nio.file.Path

@Inject
class SkillBillUpdateService(
  private val updateCheckService: UpdateCheckService,
  private val installerScriptFetchPort: InstallerScriptFetchPort,
  private val installerProcessPort: InstallerProcessPort,
) {
  fun plan(request: UpdateRunRequest): UpdateRunPlan = buildPlan(request)

  fun run(request: UpdateRunRequest): UpdateRunResult {
    val plan = buildPlan(request)
    if (request.dryRun) {
      return UpdateRunResult(status = UpdateRunStatus.DRY_RUN, exitCode = 0, plan = plan)
    }
    if (request.releaseTag == null) {
      val updateCheck = updateCheckService.check(includePrereleases = request.includePrereleases)
      if (updateCheck.status != UpdateCheckStatus.UPDATE_AVAILABLE) {
        val exitCode = if (updateCheck.status == UpdateCheckStatus.UNKNOWN) 1 else 0
        val status =
          if (updateCheck.status == UpdateCheckStatus.UNKNOWN) UpdateRunStatus.CHECK_FAILED else UpdateRunStatus.SKIPPED
        return UpdateRunResult(
          status = status,
          exitCode = exitCode,
          plan = plan,
          updateCheck = updateCheck,
          reason = updateSkipReason(updateCheck),
        )
      }
    }
    var fetchedScriptPath: Path? = null
    try {
      when (val fetched = installerScriptFetchPort.fetch(InstallerScriptFetchRequest(plan.scriptUrl))) {
        is InstallerScriptFetchResult.Failed ->
          return UpdateRunResult(
            status = UpdateRunStatus.DOWNLOAD_FAILED,
            exitCode = 1,
            plan = plan,
            installerOutput = fetched.message,
          )
        is InstallerScriptFetchResult.Ready -> {
          fetchedScriptPath = fetched.scriptPath
          val environment =
            request.environment.toMutableMap().apply {
              put("HOME", request.userHome.toString())
            }
          val processResult =
            installerProcessPort.run(
              InstallerProcessRequest(
                executable = "bash",
                arguments = listOf(fetched.scriptPath.toString()) + plan.installerArgs,
                environment = environment,
              ),
            )
          val status = if (processResult.exitCode == 0) UpdateRunStatus.COMPLETED else UpdateRunStatus.FAILED
          return UpdateRunResult(
            status = status,
            exitCode = processResult.exitCode,
            plan = plan,
            installerOutput = processResult.output,
          )
        }
      }
    } finally {
      fetchedScriptPath?.let(installerScriptFetchPort::cleanup)
    }
  }

  private fun buildPlan(request: UpdateRunRequest): UpdateRunPlan {
    val installerArgs =
      buildList {
        add("--reuse-last-selection")
        request.releaseTag?.let {
          add("--release")
          add(it)
        }
        if (request.clean) add("--clean")
      }
    val command =
      buildString {
        append("fetch ")
        append(INSTALL_SCRIPT_URL)
        append(" then bash <script>")
        installerArgs.forEach { arg ->
          append(' ')
          append(shellQuote(arg))
        }
      }
    return UpdateRunPlan(command = command, installerArgs = installerArgs, scriptUrl = INSTALL_SCRIPT_URL)
  }
}

private fun updateSkipReason(updateCheck: UpdateCheckResult): String =
  when (updateCheck.status) {
    UpdateCheckStatus.UP_TO_DATE -> "installed version is already the latest release"
    UpdateCheckStatus.AHEAD_OF_RELEASE -> "installed version is newer than the latest release"
    UpdateCheckStatus.UNKNOWN -> "could not determine the latest release"
    UpdateCheckStatus.UPDATE_AVAILABLE -> "update is available"
  }

private val SHELL_SAFE_PATTERN = Regex("[A-Za-z0-9_./:=@%+-]+")

private fun shellQuote(value: String): String =
  if (SHELL_SAFE_PATTERN.matches(value)) {
    value
  } else {
    "'${value.replace("'", "'\"'\"'")}'"
  }
