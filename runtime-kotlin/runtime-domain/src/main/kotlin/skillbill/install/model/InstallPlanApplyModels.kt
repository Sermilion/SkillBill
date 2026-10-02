package skillbill.install.model

import skillbill.error.core.RuntimeFailureCode
import skillbill.model.FileLocation

enum class InstallPlanSkillKind {
  BASE,
  PLATFORM_PACK,
}

data class InstallPlanSkill(
  val name: String,
  val sourceDir: FileLocation,
  val kind: InstallPlanSkillKind,
  val platformSlug: String? = null,
  val internalFor: String? = null,
)

data class PlannedPlatformPack(
  val slug: String,
  val packRoot: FileLocation,
  val selected: Boolean,
)

data class InstallStagingPathIntent(
  val skillName: String,
  val sourceDir: FileLocation,
  val stagingRoot: FileLocation,
  val stagingDir: FileLocation,
  val contentHash: String,
)

data class InstallStagingIntent(
  val root: FileLocation,
  val skillPaths: List<InstallStagingPathIntent>,
)

data class McpRegistrationIntent(
  val register: Boolean,
  val runtimeMcpBin: FileLocation?,
  val agents: List<SupportedAgent>,
)

data class InstallPlan(
  val request: InstallPlanRequest,
  val agents: List<InstallAgentTarget>,
  val discoveredPlatformPacks: List<PlannedPlatformPack>,
  val selectedPlatformSlugs: List<String>,
  val skills: List<InstallPlanSkill>,
  val staging: InstallStagingIntent,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationIntent: McpRegistrationIntent,
  val runtimeDistributionInputs: RuntimeDistributionInputs,
  val installationTargetPaths: InstallationTargetPaths,
  val windowsSymlinkPreflight: WindowsSymlinkPreflight,
)

data class InstallTransaction(
  val createdSymlinks: List<FileLocation> = emptyList(),
) {
  fun withRecordedSymlink(path: FileLocation): InstallTransaction = copy(createdSymlinks = createdSymlinks + path)
}

data class McpProfileOutcome(val configPath: FileLocation, val changed: Boolean)

data class McpMutationResult(
  val agent: String,
  val configPath: FileLocation,
  val changed: Boolean,
  val profiles: List<McpProfileOutcome> = emptyList(),
)

sealed interface McpRegistrationOutcome {
  data class Applied(val mutation: McpMutationResult) : McpRegistrationOutcome

  data class ProfilesFailed(
    val message: String,
    val succeeded: List<McpProfileOutcome>,
  ) : McpRegistrationOutcome
}

enum class McpRegistrationFailureCode : RuntimeFailureCode {
  PROFILE_UPDATE_FAILED,
}

data class RenderedSkill(
  val skillName: String,
  val sourceSkillDir: FileLocation,
  val stagingDir: FileLocation,
  val renderedSkillFile: FileLocation,
  val renderedPointerFiles: List<FileLocation>,
  val copiedAuthoredFiles: List<FileLocation>,
  val contentHash: String,
  val renderedSidecarFiles: List<FileLocation> = emptyList(),
)

enum class InstallApplyStatus {
  SUCCESS,
  WARNING,
  FAILURE,
}

enum class InstallApplyIssueKind {
  STAGING_FAILED,
  SKILL_LINK_FAILED,
  NATIVE_AGENT_LINK_FAILED,
  TELEMETRY_APPLY_FAILED,
  MCP_REGISTRATION_FAILED,
  WINDOWS_SYMLINK_PRECHECK_FAILED,
  WINDOWS_SYMLINK_WARNING,
  REPO_LOCAL_CONFIG_SCAFFOLD_FAILED,
}

data class InstallApplyIssue(
  val kind: InstallApplyIssueKind,
  val message: String,
  val skillName: String? = null,
  val agent: SupportedAgent? = null,
  val path: FileLocation? = null,
  val guidance: String? = null,
  val causeClass: String? = null,
)

enum class InstallSkillStagingStatus {
  STAGED,
  FAILED,
}

data class InstallSkillStagingOutcome(
  val status: InstallSkillStagingStatus,
  val sourceDir: FileLocation,
  val stagingDir: FileLocation? = null,
  val renderedSkillFile: FileLocation? = null,
  val renderedPointerFiles: List<FileLocation> = emptyList(),
  val copiedAuthoredFiles: List<FileLocation> = emptyList(),
  val contentHash: String? = null,
  val issue: InstallApplyIssue? = null,
  val renderedSidecarFiles: List<FileLocation> = emptyList(),
)

enum class InstallAgentLinkStatus {
  CREATED,
  SKIPPED,
  WARNING,
  FAILED,
}

enum class WindowsSymlinkFallbackState {
  NOT_REQUIRED,
  PROCEEDING,
  USER_ACTION_REQUIRED,
  LINK_FAILED,
}

data class WindowsSymlinkApplyOutcome(
  val preflight: WindowsSymlinkPreflight,
  val fallbackState: WindowsSymlinkFallbackState,
  val guidance: String = "",
)

data class InstallAgentSkillLinkOutcome(
  val agent: SupportedAgent,
  val targetDir: FileLocation,
  val linkPath: FileLocation,
  val linkTarget: FileLocation,
  val status: InstallAgentLinkStatus,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

data class ResolvedInstalledAgents(
  val agents: Set<SupportedAgent>,
) {
  companion object {
    val EMPTY: ResolvedInstalledAgents = ResolvedInstalledAgents(emptySet())

    fun fromApplyResult(
      status: InstallApplyStatus,
      skills: List<InstallAppliedSkill>,
    ): ResolvedInstalledAgents {
      if (status == InstallApplyStatus.FAILURE) {
        return EMPTY
      }
      return fromSuccessfulApplyOutcomes(skills)
    }

    fun fromSuccessfulApplyOutcomes(skills: List<InstallAppliedSkill>): ResolvedInstalledAgents {
      val resolvedAgents =
        skills
          .flatMap(InstallAppliedSkill::links)
          .filter { link ->
            link.status == InstallAgentLinkStatus.CREATED || link.status == InstallAgentLinkStatus.SKIPPED
          }
          .mapTo(mutableSetOf(), InstallAgentSkillLinkOutcome::agent)
      return ResolvedInstalledAgents(resolvedAgents)
    }
  }
}

data class InstallAppliedSkill(
  val skillName: String,
  val kind: InstallPlanSkillKind,
  val platformSlug: String? = null,
  val sourceDir: FileLocation,
  val staging: InstallSkillStagingOutcome,
  val links: List<InstallAgentSkillLinkOutcome> = emptyList(),
)

enum class NativeAgentProviderId(
  val agent: SupportedAgent,
) {
  CLAUDE(SupportedAgent.CLAUDE),
  CODEX(SupportedAgent.CODEX),
  JUNIE(SupportedAgent.JUNIE),
  CURSOR(SupportedAgent.CURSOR),
  ;

  val id: String get() = agent.wireValue
}

enum class NativeAgentApplyStatus {
  LINKED,
  SKIPPED,
  WARNING,
  FAILED,
}

data class NativeAgentApplyOutcome(
  val provider: NativeAgentProviderId,
  val agent: SupportedAgent,
  val status: NativeAgentApplyStatus,
  val path: FileLocation? = null,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

enum class InstallTelemetryApplyStatus {
  SUCCESS,
  SKIPPED,
  FAILED,
}

data class InstallTelemetryApplyOutcome(
  val level: InstallTelemetryLevel,
  val status: InstallTelemetryApplyStatus,
  val configPath: FileLocation? = null,
  val clearedEvents: Int = 0,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
)

enum class McpRegistrationApplyStatus {
  SUCCESS,
  SKIPPED,
  FAILED,
}

data class McpRegistrationApplyOutcome(
  val agent: SupportedAgent,
  val status: McpRegistrationApplyStatus,
  val configPath: FileLocation? = null,
  val changed: Boolean = false,
  val message: String = "",
  val issue: InstallApplyIssue? = null,
  val profiles: List<McpProfileOutcome> = emptyList(),
)

data class InstallApplyResult(
  val status: InstallApplyStatus,
  val skills: List<InstallAppliedSkill>,
  val nativeAgents: List<NativeAgentApplyOutcome>,
  val telemetryOutcome: InstallTelemetryApplyOutcome,
  val mcpRegistrationOutcomes: List<McpRegistrationApplyOutcome>,
  val warnings: List<InstallApplyIssue>,
  val failures: List<InstallApplyIssue>,
  val windowsSymlinkOutcome: WindowsSymlinkApplyOutcome,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationIntent: McpRegistrationIntent,
) {
  val resolvedInstalledAgents: ResolvedInstalledAgents
    get() = ResolvedInstalledAgents.fromApplyResult(status, skills)
}
