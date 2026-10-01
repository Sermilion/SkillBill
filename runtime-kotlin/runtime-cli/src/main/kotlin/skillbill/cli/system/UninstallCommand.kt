package skillbill.cli.system

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.uninstall.SkillBillUninstallService
import skillbill.application.uninstall.model.UninstallPlan
import skillbill.application.uninstall.model.UninstallRequest
import skillbill.application.uninstall.model.UninstallResult
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens

@Inject
class UninstallCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val uninstallService: SkillBillUninstallService,
) : DocumentedCliCommand("uninstall", "Uninstall Skill Bill from local agents and runtime state.") {
  private val yes by option("--yes", "-y", help = "Skip the interactive confirmation prompt.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Show what would be removed without changing files.")
    .flag(default = false)
  private val desktopAppDir by option("--desktop-app-dir", help = "Override the desktop app install directory.")
  private val format by formatOption()

  override fun run() {
    if (inputs.environment[GOAL_CONTINUATION_ENV] == "1") {
      val message =
        "Refusing to run skill-bill uninstall during skill-bill goal-continuation.\n" +
          "Goal workers must preserve the active workflow store; uninstall after the goal completes."
      state.completeText(
        message,
        mapOf(
          SharedPayloadKeys.STATUS to "error",
          "error" to message,
          "exit_code" to GOAL_CONTINUATION_REFUSAL_EXIT_CODE,
        ),
        exitCode = GOAL_CONTINUATION_REFUSAL_EXIT_CODE,
      )
      return
    }

    val request =
      UninstallRequest(
        home = inputs.userHome,
        environment = inputs.environment,
        desktopAppDir = desktopAppDir,
      )
    val plan = uninstallService.plan(request)
    if (!dryRun && !yes && !confirmed(plan)) {
      val payload =
        plan.toPayload(
          status = "aborted",
          removed = emptyList(),
          skipped = emptyList(),
          warnings = emptyList(),
        )
      completeUninstall("uninstall_status: aborted\n", payload, exitCode = 1)
      return
    }

    if (dryRun) {
      completeUninstall(plan.toText("dry_run"), plan.toPayload("dry_run", emptyList(), emptyList(), emptyList()))
      return
    }

    val result = uninstallService.apply(plan)
    completeUninstall(result.toText(), result.toPayload(), result.exitCode)
  }

  private fun confirmed(plan: UninstallPlan): Boolean {
    inputs.liveStdout(plan.confirmationText())
    val answer = state.readInputLine()?.trim().orEmpty()
    return answer.equals("y", ignoreCase = true) || answer.equals("yes", ignoreCase = true)
  }

  private fun completeUninstall(
    text: String,
    payload: Map<String, Any?>,
    exitCode: Int = 0,
  ) {
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode)
    } else {
      state.completeText(text, payload, exitCode)
    }
  }
}

private val GOAL_CONTINUATION_ENV = FeatureTaskRuntimeGoalContinuationLaunchTokens.GOAL_CONTINUATION_ENV
private const val GOAL_CONTINUATION_REFUSAL_EXIT_CODE = 64

private fun UninstallPlan.confirmationText(): String =
  buildString {
    appendLine("This will uninstall Skill Bill from:")
    appendLine("- ${agentTargets.size} agent target directories")
    appendLine("- ${mcpAgents.size} MCP configurations")
    appendLine("- $stateRoot")
    append("Continue? [y/N] ")
  }

private fun UninstallPlan.toText(status: String): String =
  buildString {
    appendLine("uninstall_status: $status")
    appendLine("state_root: $stateRoot")
    appendLine("agent_targets: ${agentTargets.size}")
    appendLine("skill_names: ${skillNames.size}")
  }

private fun UninstallResult.toText(): String =
  buildString {
    appendLine("uninstall_status: $status")
    appendLine("removed: ${removed.size}")
    appendLine("skipped: ${skipped.size}")
    if (warnings.isNotEmpty()) {
      appendLine("warnings:")
      warnings.forEach { warning -> appendLine("- $warning") }
    }
  }

private fun UninstallPlan.toPayload(
  status: String,
  removed: List<String>,
  skipped: List<String>,
  warnings: List<String>,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status,
    "state_root" to stateRoot.toString(),
    "skill_names" to skillNames,
    "legacy_names" to legacyNames,
    "agent_targets" to agentTargets.map { it.toString() },
    "mcp_agents" to mcpAgents,
    "launchers" to
      launchers.map {
        mapOf("path" to it.path.toString(), "expected_target" to it.expectedTarget.toString())
      },
    "desktop" to
      mapOf(
        "launcher" to desktop.launcher?.path?.toString(),
        "files" to desktop.files.map { it.toString() },
        "directories" to desktop.directories.map { it.toString() },
      ),
    "removed" to removed,
    "skipped" to skipped,
    "warnings" to warnings,
  )

private fun UninstallResult.toPayload(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to status,
    "removed" to removed,
    "skipped" to skipped,
    "warnings" to warnings,
  )
