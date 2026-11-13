package skillbill.install.model

import skillbill.model.FileLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInstallSelectionModelTest {
  @Test
  fun `shared install selection contains only runtime install choices`() {
    assertEquals(
      setOf("selectedAgents", "platformPackSelection", "telemetryLevel", "mcpRegistrationChoice"),
      SharedInstallSelection::class.java.declaredFields
        .filterNot { field -> field.isSynthetic }
        .mapTo(mutableSetOf()) { field -> field.name },
    )
  }

  @Test
  fun `resolved installed agents derive only reusable links from non failure apply results`() {
    val successResult =
      installApplyResult(
        status = InstallApplyStatus.SUCCESS,
        links =
          listOf(
            link(SupportedAgent.CODEX, InstallAgentLinkStatus.CREATED),
            link(SupportedAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
            link(SupportedAgent.CURSOR, InstallAgentLinkStatus.FAILED),
          ),
      )
    val warningResult =
      installApplyResult(
        status = InstallApplyStatus.WARNING,
        links =
          listOf(
            link(SupportedAgent.CODEX, InstallAgentLinkStatus.CREATED),
            link(SupportedAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
            link(SupportedAgent.CURSOR, InstallAgentLinkStatus.WARNING),
          ),
      )
    val failureResult =
      installApplyResult(
        status = InstallApplyStatus.FAILURE,
        links =
          listOf(
            link(SupportedAgent.CODEX, InstallAgentLinkStatus.CREATED),
            link(SupportedAgent.CLAUDE, InstallAgentLinkStatus.SKIPPED),
          ),
      )

    assertEquals(setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE), successResult.resolvedInstalledAgents.agents)
    assertEquals(setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE), warningResult.resolvedInstalledAgents.agents)
    assertEquals(emptySet(), failureResult.resolvedInstalledAgents.agents)
  }

  private fun installApplyResult(
    status: InstallApplyStatus,
    links: List<InstallAgentSkillLinkOutcome>,
  ): InstallApplyResult =
    InstallApplyResult(
      status = status,
      skills =
        listOf(
          InstallAppliedSkill(
            skillName = "bill-code-review",
            kind = InstallPlanSkillKind.BASE,
            sourceDir = FileLocation("/repo/skills/bill-code-review"),
            staging =
              InstallSkillStagingOutcome(
                status = InstallSkillStagingStatus.STAGED,
                sourceDir = FileLocation("/repo/skills/bill-code-review"),
                stagingDir = FileLocation("/home/.skill-bill/installed-skills/bill-code-review-hash"),
              ),
            links = links,
          ),
        ),
      nativeAgents = emptyList(),
      telemetryOutcome =
        InstallTelemetryApplyOutcome(
          level = InstallTelemetryLevel.ANONYMOUS,
          status = InstallTelemetryApplyStatus.SUCCESS,
        ),
      mcpRegistrationOutcomes =
        listOf(
          McpRegistrationApplyOutcome(
            agent = SupportedAgent.CURSOR,
            status = McpRegistrationApplyStatus.SUCCESS,
          ),
        ),
      warnings = emptyList(),
      failures = emptyList(),
      windowsSymlinkOutcome =
        WindowsSymlinkApplyOutcome(
          preflight =
            WindowsSymlinkPreflight(
              state = WindowsSymlinkPreflightState.NOT_WINDOWS,
              decision = WindowsSymlinkDecision.NOT_REQUIRED,
            ),
          fallbackState = WindowsSymlinkFallbackState.NOT_REQUIRED,
        ),
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationIntent =
        McpRegistrationIntent(
          register = true,
          runtimeMcpBin = FileLocation("/runtime-mcp"),
          agents = listOf(SupportedAgent.CURSOR),
        ),
    )

  private fun link(
    agent: SupportedAgent,
    status: InstallAgentLinkStatus,
  ): InstallAgentSkillLinkOutcome =
    InstallAgentSkillLinkOutcome(
      agent = agent,
      targetDir = FileLocation("/home/.${agent.id}/skills"),
      linkPath = FileLocation("/home/.${agent.id}/skills/bill-code-review"),
      linkTarget = FileLocation("/home/.skill-bill/installed-skills/bill-code-review-hash"),
      status = status,
    )
}
