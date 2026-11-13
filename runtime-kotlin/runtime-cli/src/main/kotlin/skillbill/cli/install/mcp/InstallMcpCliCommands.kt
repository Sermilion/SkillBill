package skillbill.cli.install.mcp

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.cli.install.apply.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.model.McpRegistrationFailureCode
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpProfileEnvironment
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import java.nio.file.Path

@Inject
class InstallRegisterMcpCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installMcpRegistrationPort: InstallMcpRegistrationPort,
) : DocumentedCliCommand("register-mcp", "Register Skill Bill's packaged Kotlin MCP server for one agent.") {
  private val agent by argument(help = "Agent name.")
  private val runtimeMcpBin by option("--runtime-mcp-bin", help = "Packaged runtime-mcp bin script.").required()

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "register-mcp")) {
      return
    }
    val outcome =
      installMcpRegistrationPort.registerMcp(
        InstallMcpRegistrationRequest(
          agent = agent,
          runtimeMcpBin = Path.of(runtimeMcpBin),
          home = inputs.userHome,
          profileEnvironment = inputs.mcpProfileEnvironment(),
        ),
      ).outcome
    val result =
      when (outcome) {
        is McpRegistrationOutcome.Applied -> outcome.mutation
        is McpRegistrationOutcome.ProfilesFailed -> throw profilesFailure(outcome)
      }
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
  }
}

@Inject
class InstallUnregisterMcpCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installMcpRegistrationPort: InstallMcpRegistrationPort,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
  private val diagnostics: RuntimeDiagnostics,
) : DocumentedCliCommand("unregister-mcp", "Remove Skill Bill MCP registration for one agent.") {
  private val agent by argument(help = "Agent name.")
  private val runtimeMcpBin by option("--runtime-mcp-bin", help = "Expected Skill Bill launcher identity.")

  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "unregister-mcp")) {
      return
    }
    val expectedRuntimeMcpBin = runtimeMcpBin?.let(Path::of) ?: recordedOpenCodeLauncher()
    val outcome =
      installMcpRegistrationPort.unregisterMcp(
        InstallMcpUnregistrationRequest(
          agent = agent,
          home = inputs.userHome,
          profileEnvironment = inputs.mcpProfileEnvironment(),
          expectedRuntimeMcpBin =
            if (agent.equals(SupportedAgent.OPENCODE.id, ignoreCase = true)) expectedRuntimeMcpBin else null,
        ),
      ).outcome
    val result =
      when (outcome) {
        is McpRegistrationOutcome.Applied -> outcome.mutation
        is McpRegistrationOutcome.ProfilesFailed -> {
          val removed = changedProfilePathsText(outcome.succeeded)
          if (removed.isNotEmpty()) {
            inputs.liveStdout("$removed\n")
          }
          throw profilesFailure(outcome)
        }
      }
    state.completeText(mcpProfilePathsText(result), mcpProfilesMap(agent, result))
  }

  private fun recordedOpenCodeLauncher(): Path? {
    if (!agent.equals(SupportedAgent.OPENCODE.id, ignoreCase = true)) return null
    val selection =
      try {
        installSelectionPersistencePort.readLatestSuccessfulSelection(
          ReadLatestSuccessfulInstallSelectionRequest(inputs.userHome),
        ).selection
      } catch (error: SkillBillRuntimeException) {
        if (error.code != InstallFailureCode.MISSING_INSTALL_SELECTION_RECORD) throw error
        diagnostics.info(
          "No saved OpenCode launcher identity is available; existing entries require --runtime-mcp-bin.",
        )
        return null
      }
    if (SupportedAgent.OPENCODE !in selection.selectedAgents || !selection.mcpRegistrationChoice.register) return null
    return selection.mcpRegistrationChoice.runtimeMcpBin?.let { Path.of(it.value) }
  }
}

private fun profilesFailure(failure: McpRegistrationOutcome.ProfilesFailed): SkillBillRuntimeException =
  SkillBillRuntimeException(McpRegistrationFailureCode.PROFILE_UPDATE_FAILED, failure.message)

private fun mcpProfilePathsText(result: McpMutationResult): String =
  (
    if (result.profiles.isEmpty()) {
      result.configPath.toString()
    } else {
      changedProfilePathsText(result.profiles)
    }
  ) + result.coverage?.let { "\n$it" }.orEmpty()

private fun changedProfilePathsText(profiles: List<McpProfileOutcome>): String =
  profiles
    .filter { it.changed }
    .joinToString("\n") { it.configPath.toString() }

private fun mcpProfilesMap(
  agent: String,
  result: McpMutationResult,
): Map<String, Any?> =
  mapOf(
    "agent" to agent,
    "changed" to result.changed,
    "profiles" to
      result.profiles.map { profile ->
        mapOf("config_path" to profile.configPath.toString(), "changed" to profile.changed)
      },
  )

private fun CliRunInputs.mcpProfileEnvironment(): InstallMcpProfileEnvironment {
  val profile = InstallMcpProfileEnvironment.from(environment) ?: InstallMcpProfileEnvironment()
  return profile.copy(
    databasePath = databasePath ?: profile.databasePath,
    workingDirectory = workingDirectory.toString(),
  )
}
