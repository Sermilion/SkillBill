package skillbill.infrastructure.skills.install.apply
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.SkillContentIdentityMismatchError
import skillbill.infrastructure.skills.install.plan.discoverPlatformManifests
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit.FAILURE
import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingOutcome
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.RenderedSkill
import skillbill.install.model.WindowsSymlinkApplyOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.ports.telemetry.transport.TelemetryLevelMutator
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun applyInstallPlan(
  plan: InstallPlan,
  telemetryLevelMutator: TelemetryLevelMutator? = null,
  telemetryConfigStore: TelemetryConfigStore? = null,
  mcpRegistrationPort: InstallMcpRegistrationPort,
  catalogLoader: PlatformPackCatalogLoader? = null,
): InstallApplyResult {
  val warnings = mutableListOf<InstallApplyIssue>()
  val failures = mutableListOf<InstallApplyIssue>()
  val windowsOutcome = windowsSymlinkApplyOutcome(plan)
  collectWindowsSymlinkIssues(plan, windowsOutcome, warnings, failures)
  if (failures.isNotEmpty()) {
    return InstallApplyResult(
      status = InstallApplyStatus.FAILURE,
      skills = emptyList(),
      nativeAgents = emptyList(),
      telemetryOutcome = skippedTelemetryOutcome(plan, "Skipped because install preflight failed."),
      mcpRegistrationOutcomes = skippedMcpRegistrationOutcomes(plan, "Skipped because install preflight failed."),
      warnings = warnings,
      failures = failures,
      windowsSymlinkOutcome = windowsOutcome,
      telemetryLevel = plan.telemetryLevel,
      mcpRegistrationIntent = plan.mcpRegistrationIntent,
    )
  }
  val platformManifests = discoverPlatformManifests(plan.request, catalogLoader = catalogLoader)
  cleanupExistingSkillBillLinks(plan, platformManifests, failures)
  val appliedSkills = applyPlannedSkills(plan, platformManifests, failures, catalogLoader)
  if (failures.isEmpty()) {
    materializeAgentPlatformPackViews(plan, platformManifests, appliedSkills, failures)
  }
  val nativeAgents =
    if (failures.isEmpty()) {
      applyRepoLocalConfigScaffold(plan, warnings)
      applyNativeAgents(plan, failures, catalogLoader)
    } else {
      emptyList()
    }
  val finalWindowsOutcome = windowsOutcome.withSymlinkFailureState(failures)
  val telemetryOutcome =
    if (failures.isEmpty()) {
      applyTelemetryIntent(plan, warnings, telemetryLevelMutator, telemetryConfigStore)
    } else {
      skippedTelemetryOutcome(plan, "Skipped because install apply failed.")
    }
  val mcpRegistrationOutcomes =
    if (failures.isEmpty()) {
      applyMcpRegistrationIntent(plan, warnings, mcpRegistrationPort)
    } else {
      skippedMcpRegistrationOutcomes(plan, "Skipped because install apply failed.")
    }
  return InstallApplyResult(
    status = aggregateApplyStatus(warnings, failures),
    skills = appliedSkills,
    nativeAgents = nativeAgents,
    telemetryOutcome = telemetryOutcome,
    mcpRegistrationOutcomes = mcpRegistrationOutcomes,
    warnings = warnings,
    failures = failures,
    windowsSymlinkOutcome = finalWindowsOutcome,
    telemetryLevel = plan.telemetryLevel,
    mcpRegistrationIntent = plan.mcpRegistrationIntent,
  )
}

private fun collectWindowsSymlinkIssues(
  plan: InstallPlan,
  windowsOutcome: WindowsSymlinkApplyOutcome,
  warnings: MutableList<InstallApplyIssue>,
  failures: MutableList<InstallApplyIssue>,
) {
  when (windowsOutcome.fallbackState) {
    WindowsSymlinkFallbackState.USER_ACTION_REQUIRED ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.WINDOWS_SYMLINK_PRECHECK_FAILED,
          message =
            plan.windowsSymlinkPreflight.message.ifBlank {
              "Windows requires Developer Mode or an elevated shell before creating symlinks."
            },
          guidance = windowsOutcome.guidance,
        ),
      )
    WindowsSymlinkFallbackState.PROCEEDING ->
      warnings.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.WINDOWS_SYMLINK_WARNING,
          message = plan.windowsSymlinkPreflight.message,
          guidance = windowsOutcome.guidance.takeIf(String::isNotBlank),
        ),
      )
    WindowsSymlinkFallbackState.NOT_REQUIRED,
    WindowsSymlinkFallbackState.LINK_FAILED,
    -> Unit
  }
}

private fun applyPlannedSkills(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
  catalogLoader: PlatformPackCatalogLoader? = null,
): List<InstallAppliedSkill> =
  standaloneInstallableSkills(
    plan.skills,
    plan.selectedPlatformSlugs.toSet(),
  ).map { skill ->
    val staging =
      stagePlannedSkill(
        plan = plan,
        skill = skill,
        platformManifests = platformManifests,
        failures = failures,
        catalogLoader = catalogLoader,
      )
    val links =
      staging.stagingDir?.takeIf { staging.status == InstallSkillStagingStatus.STAGED }
        ?.let { stagingDir ->
          linkPlannedSkill(
            skill = skill,
            stagingDir = stagingDir.toPath(),
            plan = plan,
            failures = failures,
          )
        }
        .orEmpty()
    InstallAppliedSkill(
      skillName = skill.name,
      kind = skill.kind,
      platformSlug = skill.platformSlug,
      sourceDir = skill.sourceDir,
      staging = staging,
      links = links,
    )
  }

internal fun standaloneInstallableSkills(
  skills: List<InstallPlanSkill>,
  selectedPlatformSlugs: Set<String>,
): List<InstallPlanSkill> =
  skills.filter { skill ->

    skill.internalFor == null &&
      (skill.kind == InstallPlanSkillKind.BASE || skill.platformSlug in selectedPlatformSlugs)
  }

private fun stagePlannedSkill(
  plan: InstallPlan,
  skill: InstallPlanSkill,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
  catalogLoader: PlatformPackCatalogLoader? = null,
): InstallSkillStagingOutcome =
  runCatching {
    val intent = plannedStagingIntent(plan, skill)
    val staging =
      validatedPlannedStaging(
        plan = plan,
        skill = skill,
        intent = intent,
        platformManifests = platformManifests,
        catalogLoader = catalogLoader,
      )
    staging.toStagingOutcome(skill.sourceDir.toPath())
  }.getOrElse { error ->

    if (error is SkillContentIdentityMismatchError) throw error
    failedStagingOutcome(skill.sourceDir.toPath(), skill.name, error).also { outcome ->
      outcome.issue?.let(failures::add)
    }
  }

private fun RenderedSkill.toStagingOutcome(sourceDir: Path): InstallSkillStagingOutcome =
  InstallSkillStagingOutcome(
    status = InstallSkillStagingStatus.STAGED,
    sourceDir = sourceDir.toFileLocation(),
    stagingDir = stagingDir,
    renderedSkillFile = renderedSkillFile,
    renderedPointerFiles = renderedPointerFiles,
    copiedAuthoredFiles = copiedAuthoredFiles,
    contentHash = contentHash,
    renderedSidecarFiles = renderedSidecarFiles,
  )

private fun failedStagingOutcome(
  sourceDir: Path,
  skillName: String,
  error: Throwable,
): InstallSkillStagingOutcome {
  val issue =
    InstallApplyIssue(
      kind = InstallApplyIssueKind.STAGING_FAILED,
      message = error.message.orEmpty(),
      skillName = skillName,
      path = sourceDir.toFileLocation(),
      causeClass = error.failureCodeLabel() ?: error::class.qualifiedName,
    )
  return InstallSkillStagingOutcome(
    status = InstallSkillStagingStatus.FAILED,
    sourceDir = sourceDir.toFileLocation(),
    issue = issue,
  )
}

private fun windowsSymlinkApplyOutcome(plan: InstallPlan): WindowsSymlinkApplyOutcome {
  val preflight = plan.windowsSymlinkPreflight
  val fallbackState =
    when (preflight.decision) {
      WindowsSymlinkDecision.REQUIRE_USER_ACTION -> WindowsSymlinkFallbackState.USER_ACTION_REQUIRED
      WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS ->
        when (preflight.state) {
          WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE -> WindowsSymlinkFallbackState.PROCEEDING
          WindowsSymlinkPreflightState.AVAILABLE,
          WindowsSymlinkPreflightState.NOT_WINDOWS,
          WindowsSymlinkPreflightState.DECISION_REQUIRED,
          -> WindowsSymlinkFallbackState.NOT_REQUIRED
        }
      WindowsSymlinkDecision.NOT_REQUIRED ->
        when (preflight.state) {
          WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE -> WindowsSymlinkFallbackState.PROCEEDING
          WindowsSymlinkPreflightState.AVAILABLE,
          WindowsSymlinkPreflightState.NOT_WINDOWS,
          WindowsSymlinkPreflightState.DECISION_REQUIRED,
          -> WindowsSymlinkFallbackState.NOT_REQUIRED
        }
    }
  return WindowsSymlinkApplyOutcome(
    preflight = preflight,
    fallbackState = fallbackState,
    guidance = windowsSymlinkGuidance(),
  )
}

private fun WindowsSymlinkApplyOutcome.withSymlinkFailureState(
  failures: List<InstallApplyIssue>,
): WindowsSymlinkApplyOutcome {
  if (fallbackState == WindowsSymlinkFallbackState.USER_ACTION_REQUIRED) {
    return this
  }
  val symlinkCause = InstallSymlinkException::class.qualifiedName
  val hasSymlinkFailure = failures.any { issue -> issue.causeClass == symlinkCause }
  return if (hasSymlinkFailure) {
    copy(fallbackState = WindowsSymlinkFallbackState.LINK_FAILED, guidance = windowsSymlinkGuidance())
  } else {
    this
  }
}

private fun aggregateApplyStatus(
  warnings: List<InstallApplyIssue>,
  failures: List<InstallApplyIssue>,
): InstallApplyStatus =
  when {
    failures.isNotEmpty() -> InstallApplyStatus.FAILURE
    warnings.isNotEmpty() -> InstallApplyStatus.WARNING
    else -> InstallApplyStatus.SUCCESS
  }
