package skillbill.cli.system

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.system.SystemService
import skillbill.application.updatecheck.SkillBillUpdateService
import skillbill.application.updatecheck.UpdateCheckService
import skillbill.application.updatecheck.model.UpdateCheckResult
import skillbill.application.updatecheck.model.UpdateRunPlan
import skillbill.application.updatecheck.model.UpdateRunRequest
import skillbill.application.updatecheck.model.UpdateRunResult
import skillbill.application.updatecheck.model.UpdateRunStatus
import skillbill.application.updatecheck.toText
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.operation.updatecheck.updateCheckContract

@Inject
class VersionCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("version", "Show the installed skill-bill version.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.version().toPayload(), format)
  }
}

@Inject
class UpdateCheckCommand(
  private val service: UpdateCheckService,
  private val state: CliRunState,
) : DocumentedCliCommand("update-check", "Check whether a Skill Bill update is available.") {
  private val includePrereleases by option(
    "--include-prereleases",
    help = "Include prerelease GitHub releases in the comparison.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.check(includePrereleases)
    if (format == CliFormat.JSON) {
      state.complete(result.toPayload(), format, exitCode = 0)
    } else {
      state.completeText(result.toText(), result.toPayload(), exitCode = 0)
    }
  }
}

@Inject
class UpdateCommand(
  private val updateService: SkillBillUpdateService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("update", "Update Skill Bill by running the official installer.") {
  private val release by option("--release", help = "Install a specific release tag instead of latest stable.")
  private val clean by option(
    "--clean",
    help = "Wipe installed skills, platform packs, and orchestration before staging the candidate tree.",
  ).flag(default = false)
  private val dryRun by option("--dry-run", help = "Print the installer command without running it.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    val request =
      UpdateRunRequest(
        releaseTag = release,
        clean = clean,
        dryRun = dryRun,
        userHome = inputs.userHome,
        environment = inputs.environment,
      )
    val result = updateService.run(request)
    val wireStatus = result.status.wireValue
    val mergedPayload =
      buildMap {
        putAll(result.plan.toPayload(wireStatus))
        put("exit_code", result.exitCode)
        put("installer_output", result.installerOutput)
        result.updateCheck?.let { put("update_check", it.toPayload()) }
        result.reason?.let { put("reason", it) }
      }
    if (format == CliFormat.JSON) {
      state.complete(mergedPayload, format, exitCode = result.exitCode)
    } else {
      state.completeText(result.toText(mergedPayload), mergedPayload, exitCode = result.exitCode)
    }
  }
}

private fun UpdateRunPlan.toPayload(status: String): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status,
    "command" to command,
    "installer_args" to installerArgs,
  )

private fun UpdateRunResult.toText(payload: Map<String, Any?>): String =
  when {
    status == UpdateRunStatus.DRY_RUN ->
      buildString {
        appendLine("status: ${payload[SharedPayloadKeys.STATUS]}")
        appendLine("command: ${plan.command}")
        appendLine("installer_args: ${plan.installerArgs}")
      }
    updateCheck != null ->
      buildString {
        val check = requireNotNull(updateCheck)
        append(check.toText())
        appendLine("update_status: ${payload[SharedPayloadKeys.STATUS]}")
        appendLine("reason: ${reason.orEmpty()}")
      }
    installerOutput != null -> installerOutput.orEmpty()
    else -> "status: ${payload[SharedPayloadKeys.STATUS]}\n"
  }

@Inject
class DoctorCliCommand(
  private val service: SystemService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("doctor", "Check skill-bill installation health.") {
  private val subject by argument(help = "Optional diagnostic subject. Use `skill` for one governed skill.")
    .optional()
  private val skillName by argument(help = "Governed skill name when diagnosing one skill.").optional()
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect when using `doctor skill`. Defaults to the invocation repository root.",
  )
  private val content by option("--content", help = "How much content.md text to include when using `doctor skill`.")
    .choice("none", "preview", "full")
    .default("preview")
  private val format by formatOption()

  override fun run() {
    if (subject == null) {
      state.complete(service.doctor().toPayload(), format)
    } else {
      val resolvedRoot = resolveCliRepositoryRoot(repoRoot, inputs).toString()
      state.completeText(
        retiredSubjectMessage(subject.orEmpty(), skillName.orEmpty(), resolvedRoot, content),
        exitCode = 1,
      )
    }
  }
}

private fun retiredSubjectMessage(
  subject: String,
  skillName: String,
  repoRoot: String,
  content: String,
): String {
  val replacementSkillName = skillName.ifBlank { "<skill-name>" }
  val replacement =
    when (subject) {
      "skill" -> "skill-bill show $replacementSkillName --repo-root $repoRoot --content $content"
      else -> "skill-bill doctor"
    }
  return when (subject) {
    "skill" -> "doctor skill was retired in SKILL-32; use `$replacement` instead."
    else -> "doctor subject '$subject' is unsupported; use `$replacement` instead."
  }
}

private fun UpdateCheckResult.toPayload(): Map<String, Any?> = updateCheckContract(this).toPayload()
