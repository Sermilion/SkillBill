package skillbill.cli.install.core

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.install.InstallService
import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.install.apply.InstallRequestCommand
import skillbill.cli.install.apply.installApplyPayload
import skillbill.cli.install.apply.installPlanPayload
import skillbill.cli.install.apply.reconcileMachineReport
import skillbill.cli.install.apply.reconcilePayload
import skillbill.cli.install.apply.refuseInstallMutationDuringGoalContinuation
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.model.CliRunInputs
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SharedInstallSelection
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import java.nio.file.Path

@Inject
class InstallPlanCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installService: InstallService,
) : InstallRequestCommand("plan", "Plan a governed Skill Bill install without mutating user files.") {
  override fun run() {
    val plan = installService.planInstall(toRequest(inputs))
    state.complete(installPlanPayload(plan, installService), format)
  }
}

@Inject
class InstallReconcileCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installService: InstallService,
) : InstallRequestCommand(
    "reconcile",
    "Reconcile a reinstall: compare upstream/local/baseline per-skill hashes and emit a machine-readable plan.",
  ) {
  private val upstreamRepoRoot by option(
    "--upstream-repo-root",
    help = "Upstream/candidate repo root containing skills/ and platform-packs/ to reconcile against the local copy.",
  ).required()
  private val upstreamSkillsRoot by option(
    "--upstream-skills",
    help = "Upstream/candidate skills root. Defaults to <upstream-repo-root>/skills.",
  )
  private val upstreamPlatformPacksRoot by option(
    "--upstream-platform-packs",
    help = "Upstream/candidate platform-packs root. Defaults to <upstream-repo-root>/platform-packs.",
  )

  private val apply by option(
    "--apply",
    help = "Apply the computed plan: install changed upstream skills into the live tree and refresh the baseline.",
  ).flag(default = false)

  override fun run() {
    val localRequest = toRequest(inputs)
    val resolvedUpstreamRepoRoot = Path.of(upstreamRepoRoot).toAbsolutePath().normalize()
    val resolvedUpstreamSkills = upstreamSkillsRoot?.let(Path::of) ?: resolvedUpstreamRepoRoot.resolve("skills")
    val resolvedUpstreamPacks =
      upstreamPlatformPacksRoot?.let(Path::of) ?: resolvedUpstreamRepoRoot.resolve("platform-packs")

    if (apply) {
      if (state.refuseInstallMutationDuringGoalContinuation(inputs, "reconcile")) {
        return
      }
      val outcome =
        installService.applyReconcile(
          InstallReconcileApplyRequest(
            home = inputs.userHome,
            upstreamRepoRoot = resolvedUpstreamRepoRoot,
            upstreamSkillsRoot = resolvedUpstreamSkills,
            upstreamPlatformPacksRoot = resolvedUpstreamPacks,
            localRepoRoot = localRequest.repoRoot.toPath(),
            localSkillsRoot = localRequest.targetPaths.skillsRoot.toPath(),
            localPlatformPacksRoot = localRequest.targetPaths.platformPacksRoot.toPath(),
            environment = inputs.environment,
          ),
        )
      completeReconcile(
        outcome.plan,
        refreshed = outcome.refreshed,
        applied = true,
        installedPaths = outcome.installedPaths,
        prunedPaths = outcome.prunedPaths,
      )
      return
    }

    val plan =
      installService.reconcile(
        InstallReconcileRequest(
          home = inputs.userHome,
          upstreamRepoRoot = resolvedUpstreamRepoRoot,
          upstreamSkillsRoot = resolvedUpstreamSkills,
          upstreamPlatformPacksRoot = resolvedUpstreamPacks,
          localRepoRoot = localRequest.repoRoot.toPath(),
          localSkillsRoot = localRequest.targetPaths.skillsRoot.toPath(),
          localPlatformPacksRoot = localRequest.targetPaths.platformPacksRoot.toPath(),
          environment = inputs.environment,
        ),
      )
    completeReconcile(plan, refreshed = false, applied = false, installedPaths = emptyList())
  }

  private fun completeReconcile(
    plan: ReconciliationPlan,
    refreshed: Boolean,
    applied: Boolean,
    installedPaths: List<String>,
    prunedPaths: List<String> = emptyList(),
  ) {
    state.completeText(
      reconcileMachineReport(
        plan,
        refreshed = refreshed,
        applied = applied,
        installedPaths = installedPaths,
        prunedPaths = prunedPaths,
      ),
      reconcilePayload(
        plan,
        refreshed = refreshed,
        applied = applied,
        installedPaths = installedPaths,
        prunedPaths = prunedPaths,
      ),
    )
  }
}

@Inject
class InstallApplyCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installService: InstallService,
  private val telemetryLevelMutator: TelemetryLevelMutator,
) : InstallRequestCommand("apply", "Apply a governed Skill Bill install through the shared runtime contract.") {
  override fun run() {
    if (state.refuseInstallMutationDuringGoalContinuation(inputs, "apply")) {
      return
    }
    val plan = installService.planInstall(toRequest(inputs))
    val result = installService.applyInstall(plan, telemetryLevelMutator)
    state.complete(
      installApplyPayload(plan, result, installService),
      format,
      exitCode = if (result.failures.isEmpty()) 0 else 1,
    )
  }
}

@Inject
class InstallReplayLastSelectionCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val installAgentService: InstallAgentService,
  private val installService: InstallService,
  private val installSelectionPersistencePort: InstallSelectionPersistencePort,
) : DocumentedCliCommand(
    "replay-last-selection",
    "Print the latest successful install choices as tab-separated replay fields.",
  ) {
  private val platformPacksRoot by option(
    "--platform-packs",
    help = "Platform packs root used to validate saved selected platform slugs.",
  ).required()
  private val skillsRoot by option(
    "--skills",
    help = "Base skills root used by the install planning fact collector.",
  ).required()

  override fun run() {
    try {
      val selection =
        installSelectionPersistencePort
          .readLatestSuccessfulSelection(ReadLatestSuccessfulInstallSelectionRequest(inputs.userHome))
          .selection
      val availablePlatformSlugs = installService.discoverPlatformPackSlugs(replayDiscoveryRequest())
      val staleSlugs = selection.platformPackSelection.selectedSlugs - availablePlatformSlugs
      require(staleSlugs.isEmpty()) {
        "Saved install selection references unavailable platform pack slug(s): " +
          "${staleSlugs.sorted().joinToString(", ")}."
      }
      state.completeText(selection.toReplayText(), emptyMap())
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfDatabaseFailure()
      state.completeText("${error.message.orEmpty()}\n", emptyMap(), exitCode = 1)
    } catch (error: IllegalArgumentException) {
      state.completeText("${error.message.orEmpty()}\n", emptyMap(), exitCode = 1)
    }
  }

  private fun replayDiscoveryRequest(): InstallPlanRequest =
    InstallPlanRequest(
      repoRoot =
        (Path.of(platformPacksRoot).toAbsolutePath().normalize().parent ?: Path.of(".").toAbsolutePath())
          .toFileLocation(),
      home = inputs.userHome.toFileLocation(),
      agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
      platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.NONE),
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationChoice = McpRegistrationChoice(register = false),
      runtimeDistributionInputs =
        RuntimeDistributionInputs(
          runtimeInstallRoot = inputs.userHome.resolve(".skill-bill/runtime").toFileLocation(),
        ),
      targetPaths =
        InstallationTargetPaths(
          skillsRoot = Path.of(skillsRoot).toFileLocation(),
          platformPacksRoot = Path.of(platformPacksRoot).toFileLocation(),
        ),
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      environment = inputs.environment,
    )

  private fun SharedInstallSelection.toReplayText(): String =
    buildString {
      selectedAgents.map(SupportedAgent::id).sorted().forEach { agentId ->
        append("agent\t")
        append(agentId)
        append('\t')
        append(installAgentService.agentPath(agentId, inputs.userHome, inputs.environment))
        append('\n')
      }
      append("platform-mode\t")
      append(platformPackSelection.mode.name.lowercase())
      append('\n')
      platformPackSelection.selectedSlugs.sorted().forEach { slug ->
        append("platform\t")
        append(slug)
        append('\n')
      }
      append("telemetry\t")
      append(telemetryLevel.id)
      append('\n')
      append("mcp\t")
      append(if (mcpRegistrationChoice.register) "register" else "skip")
      append('\n')
    }
}
