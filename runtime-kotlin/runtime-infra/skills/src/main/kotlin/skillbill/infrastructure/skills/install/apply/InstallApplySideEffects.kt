package skillbill.infrastructure.skills.install.apply
import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.host.resolveTelemetryConfigPath
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallTelemetryApplyOutcome
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.McpMutationResult
import skillbill.install.model.McpProfileOutcome
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.McpRegistrationOutcome
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.model.toPath
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.ports.telemetry.transport.writeTelemetryLevel
import skillbill.telemetry.DEFAULT_TELEMETRY_BATCH_SIZE
import skillbill.telemetry.parsePositiveTelemetryInt
import skillbill.telemetry.parseTelemetryLevelValue
import skillbill.telemetry.telemetryLevels
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal fun applyTelemetryIntent(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
  telemetryConfigStore: TelemetryConfigStore? = null,
): InstallTelemetryApplyOutcome {
  val environmentContext =
    EnvironmentContext(
      environment = resolveEnvironmentMap(plan.request.environment),
      userHome = plan.request.home.toPath(),
    )
  val configPath = resolveTelemetryConfigPath(environmentContext.environment, environmentContext.userHome)
  val existedBefore = Files.exists(configPath)
  return runCatching {
    val clearedEvents =
      applyInstallTelemetryLevel(
        plan.telemetryLevel.id,
        telemetryLevelMutator,
        telemetryConfigStore,
      )
    val status =
      if (plan.telemetryLevel.id == "off" && !existedBefore && clearedEvents == 0) {
        InstallTelemetryApplyStatus.SKIPPED
      } else {
        InstallTelemetryApplyStatus.SUCCESS
      }
    InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = status,
      configPath = configPath.toFileLocation(),
      clearedEvents = clearedEvents,
      message = telemetryOutcomeMessage(plan.telemetryLevel.id, status),
    )
  }.getOrElse { error ->
    val issue =
      InstallApplyIssue(
        kind = InstallApplyIssueKind.TELEMETRY_APPLY_FAILED,
        message = error.message.orEmpty(),
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      )
    warnings.add(issue)
    InstallTelemetryApplyOutcome(
      level = plan.telemetryLevel,
      status = InstallTelemetryApplyStatus.FAILED,
      configPath = configPath.toFileLocation(),
      message = "Telemetry setup failed.",
      issue = issue,
    )
  }
}

private fun applyInstallTelemetryLevel(
  level: String,
  telemetryLevelMutator: TelemetryLevelMutator?,
  telemetryConfigStore: TelemetryConfigStore?,
): Int {
  telemetryLevelMutator?.let { mutator ->
    return mutator.setLevel(level).clearedEvents
  }
  require(level in telemetryLevels) {
    "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}."
  }
  val configStore =
    requireNotNull(telemetryConfigStore) {
      "TelemetryConfigStore must be supplied through RuntimeComponent."
    }
  if (configStore.writeTelemetryLevel(level)) {
    validateInstallTelemetryConfig(configStore)
  }
  return 0
}

private fun validateInstallTelemetryConfig(configStore: TelemetryConfigStore) {
  val payload =
    configStore.read()?.payload
      ?: throw IllegalArgumentException("Telemetry config at '${configStore.configPath()}' is missing.")
  val telemetry =
    (payload["telemetry"] as? Map<*, *>)
      ?.entries
      ?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw IllegalArgumentException(
        "Telemetry config at '${configStore.configPath()}' must contain a 'telemetry' object.",
      )
  val level = parseTelemetryLevelValue(telemetry["level"]?.toString() ?: "anonymous", "telemetry.level")
  val installId = payload["install_id"]?.toString()?.trim().orEmpty()
  require(level == "off" || installId.isNotBlank()) {
    "Telemetry is enabled but no install_id is configured at '${configStore.configPath()}'. " +
      "Run 'skill-bill telemetry enable' to create one."
  }
  val batchSizeRaw = telemetry["batch_size"] ?: DEFAULT_TELEMETRY_BATCH_SIZE
  when (batchSizeRaw) {
    is Int -> require(batchSizeRaw > 0) { "telemetry.batch_size must be greater than zero." }
    is Number -> require(batchSizeRaw.toInt() > 0) { "telemetry.batch_size must be greater than zero." }
    else -> parsePositiveTelemetryInt(batchSizeRaw.toString(), "telemetry.batch_size")
  }
}

internal fun applyMcpRegistrationIntent(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
  mcpRegistrationPort: InstallMcpRegistrationPort,
): List<McpRegistrationApplyOutcome> {
  val intent = plan.mcpRegistrationIntent
  val runtimeMcpBin = intent.runtimeMcpBin
  return when {
    !intent.register -> skippedMcpRegistrationOutcomes(plan, "MCP registration not requested.")
    runtimeMcpBin == null ->
      intent.agents.map { agent ->
        failedMcpRegistrationOutcome(
          agent = agent,
          message = "MCP registration requested but no runtime-mcp binary was planned.",
          warnings = warnings,
        )
      }
    else ->
      intent.agents.map { agent ->
        registerMcpAgent(agent, runtimeMcpBin.toPath(), plan, warnings, mcpRegistrationPort)
      }
  }
}

internal fun skippedTelemetryOutcome(
  plan: InstallPlan,
  message: String,
): InstallTelemetryApplyOutcome =
  InstallTelemetryApplyOutcome(
    level = plan.telemetryLevel,
    status = InstallTelemetryApplyStatus.SKIPPED,
    configPath = resolveTelemetryConfigPath(emptyMap(), plan.request.home.toPath()).toFileLocation(),
    message = message,
  )

internal fun skippedMcpRegistrationOutcomes(
  plan: InstallPlan,
  message: String,
): List<McpRegistrationApplyOutcome> =
  plan.mcpRegistrationIntent.agents.map { agent ->
    McpRegistrationApplyOutcome(
      agent = agent,
      status = McpRegistrationApplyStatus.SKIPPED,
      message = message,
    )
  }

private fun telemetryOutcomeMessage(
  level: String,
  status: InstallTelemetryApplyStatus,
): String =
  when (status) {
    InstallTelemetryApplyStatus.SUCCESS -> "Telemetry level set to '$level'."
    InstallTelemetryApplyStatus.SKIPPED -> "Telemetry was already off."
    InstallTelemetryApplyStatus.FAILED -> "Telemetry setup failed."
  }

private fun registerMcpAgent(
  agent: SupportedAgent,
  runtimeMcpBin: Path,
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
  mcpRegistrationPort: InstallMcpRegistrationPort,
): McpRegistrationApplyOutcome {
  val registration =
    runCatching {
      mcpRegistrationPort.registerMcp(
        InstallMcpRegistrationRequest(
          agent = agent.id,
          runtimeMcpBin = runtimeMcpBin,
          home = plan.request.home.toPath(),
        ),
      ).outcome
    }.getOrElse { error ->
      if (error is CancellationException || error is InterruptedException) throw error
      return failedMcpRegistrationOutcome(
        agent = agent,
        message = error.message.orEmpty(),
        warnings = warnings,
        causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
      )
    }
  return when (registration) {
    is McpRegistrationOutcome.Applied -> appliedMcpRegistrationOutcome(agent, registration.mutation)
    is McpRegistrationOutcome.ProfilesFailed ->
      failedMcpRegistrationOutcome(
        agent = agent,
        message = profilesFailedMessage(registration),
        warnings = warnings,
        causeClass = PROFILE_FAILURE_CAUSE_CLASS,
        profiles = registration.succeeded,
      )
  }
}

private const val PROFILE_FAILURE_CAUSE_CLASS: String = "skillbill.install.model.ClaudeMcpProfileFailure"

private fun appliedMcpRegistrationOutcome(
  agent: SupportedAgent,
  result: McpMutationResult,
): McpRegistrationApplyOutcome =
  McpRegistrationApplyOutcome(
    agent = agent,
    status = McpRegistrationApplyStatus.SUCCESS,
    configPath = result.configPath,
    changed = result.changed,
    message = mcpRegistrationMessage(result),
    profiles = result.profiles,
  )

private fun profilesFailedMessage(failure: McpRegistrationOutcome.ProfilesFailed): String =
  if (failure.succeeded.isEmpty()) {
    failure.message
  } else {
    "${failure.message}. Already updated: ${failure.succeeded.joinToString(", ") { it.configPath.toString() }}"
  }

private fun mcpRegistrationMessage(result: McpMutationResult): String {
  val base = if (result.changed) "MCP registration updated." else "MCP registration already up to date."
  if (result.profiles.size <= 1) {
    return base
  }
  val paths = result.profiles.joinToString(", ") { it.configPath.toString() }
  return "$base Profiles (${result.profiles.size}): $paths"
}

private fun failedMcpRegistrationOutcome(
  agent: SupportedAgent,
  message: String,
  warnings: MutableList<InstallApplyIssue>,
  causeClass: String? = null,
  profiles: List<McpProfileOutcome> = emptyList(),
): McpRegistrationApplyOutcome {
  val issue =
    InstallApplyIssue(
      kind = InstallApplyIssueKind.MCP_REGISTRATION_FAILED,
      message = message,
      agent = agent,
      causeClass = causeClass,
    )
  warnings.add(issue)
  return McpRegistrationApplyOutcome(
    agent = agent,
    status = McpRegistrationApplyStatus.FAILED,
    message = if (profiles.isEmpty()) "MCP registration failed." else message,
    issue = issue,
    profiles = profiles,
  )
}
