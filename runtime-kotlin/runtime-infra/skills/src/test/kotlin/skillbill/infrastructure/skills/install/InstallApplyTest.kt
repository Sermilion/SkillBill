package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationApplyOutcome
import skillbill.install.model.McpRegistrationApplyStatus
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallApplyTest : InstallApplyTestSupport() {
  @Test
  fun `apply returns structured success for selected skills platforms agents and native agents`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".claude"))
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan = planInstallForTest(fixture.request(selectedPlatforms = setOf("kotlin")))

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")
    assertEquals(InstallTelemetryLevel.ANONYMOUS, result.telemetryLevel)
    assertEquals(InstallTelemetryApplyStatus.SUCCESS, result.telemetryOutcome.status)
    assertTrue(result.mcpRegistrationIntent.register)
    assertSuccessfulMcpOutcomes(result.mcpRegistrationOutcomes, setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE))
    val skillsByName = result.skills.associateBy { skill -> skill.skillName }
    assertEquals(
      setOf(
        PACK_SIDECAR_PARENT_SKILL,
        "bill-code-review",
        "bill-code-check",
        "bill-update-check",
      ),
      skillsByName.keys,
    )
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-update-check").kind)
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"), "unselected platform skill was applied")
    assertFalse(skillsByName.containsKey("bill-kotlin-code-check"), "internal platform quality-check skill was applied")
    result.skills.forEach { skill ->
      assertEquals(InstallSkillStagingStatus.STAGED, skill.staging.status)
      assertStagingUnderHomeCacheAndOutsideSource(fixture, skill.staging.stagingDir?.toPath(), skill.skillName)
      assertEquals(setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE), skill.links.map { link -> link.agent }.toSet())
      assertTrue(skill.links.all { link -> link.status == InstallAgentLinkStatus.CREATED })
    }
    assertSourceUnchanged(fixture.repoRoot, sourceBefore)
    assertNativeProviders(
      result.nativeAgents.map { native -> native.provider }.toSet(),
      setOf(NativeAgentProviderId.CLAUDE, NativeAgentProviderId.CODEX),
    )
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName }
    assertTrue(nativeArtifactNames.any { name -> "bill-kotlin-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-code-review-worker" in name })
    assertTrue(result.nativeAgents.any { native -> native.status == NativeAgentApplyStatus.LINKED })
    result.nativeAgents
      .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { native -> native.path }
      .forEach { link ->
        val target = readSymlinkTarget(link.toPath())
        val installedSkills = fixture.home.toAbsolutePath().normalize().resolve(".skill-bill/installed-skills")
        assertTrue(target.startsWith(installedSkills), "target=$target installedSkills=$installedSkills")
        assertFalse(target.startsWith(fixture.home.toAbsolutePath().normalize().resolve(".skill-bill/native-agents")))
      }
    assertFalse(Files.exists(fixture.home.resolve(".skill-bill/native-agents"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(result.nativeAgents.any { native -> native.provider == NativeAgentProviderId.JUNIE })
  }

  private fun assertSuccessfulMcpOutcomes(
    outcomes: List<McpRegistrationApplyOutcome>,
    expectedAgents: Set<SupportedAgent>,
  ) {
    assertEquals(expectedAgents, outcomes.map { outcome -> outcome.agent }.toSet())
    assertTrue(outcomes.all { outcome -> outcome.status == McpRegistrationApplyStatus.SUCCESS })
    outcomes.forEach { outcome ->
      assertNotNull(outcome.configPath, "successful MCP registration should report config path")
      assertTrue(outcome.changed)
      assertEquals("MCP registration updated.", outcome.message)
    }
  }

  @Test
  fun `apply skips MCP registration when plan intent opts out`() {
    val fixture = setupApplyFixture()
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
          mcpRegistrationChoice = McpRegistrationChoice(register = false),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(listOf(SupportedAgent.CODEX), result.mcpRegistrationOutcomes.map { outcome -> outcome.agent })
    assertTrue(result.mcpRegistrationOutcomes.all { outcome -> outcome.status == McpRegistrationApplyStatus.SKIPPED })
    assertTrue(result.mcpRegistrationOutcomes.all { outcome -> outcome.configPath == null && !outcome.changed })
    assertTrue(result.mcpRegistrationOutcomes.all { outcome -> outcome.message == "MCP registration not requested." })
    assertFalse(Files.exists(fixture.home.resolve(".codex/config.toml")))
  }

  @Test
  fun `apply maps MCP registration failure to a structured warning outcome`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".cursor/mcp.json")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, "{\n  \"theme\": \"cursor\",\n  \"mcpServers\": \n")
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CURSOR),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(McpRegistrationApplyStatus.FAILED, result.mcpRegistrationOutcomes.single().status)
    assertEquals("MCP registration failed.", result.mcpRegistrationOutcomes.single().message)
    assertNotNull(result.mcpRegistrationOutcomes.single().issue)
    assertTrue(
      result.warnings.any { warning ->
        warning.kind == InstallApplyIssueKind.MCP_REGISTRATION_FAILED &&
          warning.agent == SupportedAgent.CURSOR
      },
    )
    assertEquals("{\n  \"theme\": \"cursor\",\n  \"mcpServers\": \n", Files.readString(configPath))
    assertSourceUnchanged(fixture.repoRoot, sourceBefore)
  }

  @Test
  fun `reapply reports existing skill and native links as structured skipped outcomes`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    applyInstallForTest(plan)

    val second = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(
      second.skills.flatMap { skill -> skill.links }.all { link ->
        link.status == InstallAgentLinkStatus.SKIPPED && link.message.contains("already linked")
      },
    )
    assertTrue(
      second.nativeAgents.any { native ->
        native.provider == NativeAgentProviderId.CODEX &&
          native.status == NativeAgentApplyStatus.SKIPPED &&
          native.message.contains("already linked")
      },
    )
  }

  @Test
  fun `apply uses Windows preflight state as structured guidance without attempting writes`() {
    val fixture = setupApplyFixture()
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan =
      planInstallForTest(
        fixture.requestWithWindowsSymlinkPreflight(
          WindowsSymlinkPreflight(
            state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
            decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
            message = "Windows requires elevation or Developer Mode before symlink install.",
          ),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals(WindowsSymlinkFallbackState.USER_ACTION_REQUIRED, result.windowsSymlinkOutcome.fallbackState)
    assertEquals(WindowsSymlinkPreflightState.DECISION_REQUIRED, result.windowsSymlinkOutcome.preflight.state)
    assertEquals(WindowsSymlinkDecision.REQUIRE_USER_ACTION, result.windowsSymlinkOutcome.preflight.decision)
    assertContains(result.windowsSymlinkOutcome.guidance, "Developer Mode")
    assertContains(result.windowsSymlinkOutcome.guidance, "elevated shell")
    val failure =
      result.failures.single { failure ->
        failure.kind == InstallApplyIssueKind.WINDOWS_SYMLINK_PRECHECK_FAILED
      }
    assertContains(failure.guidance.orEmpty(), "Developer Mode")
    assertContains(failure.guidance.orEmpty(), "elevated shell")
    assertTrue(result.skills.isEmpty(), "preflight failure should stop before staging/linking")
    assertEquals(InstallTelemetryApplyStatus.SKIPPED, result.telemetryOutcome.status)
    assertEquals("Skipped because install preflight failed.", result.telemetryOutcome.message)
    assertEquals(
      setOf(SupportedAgent.CODEX, SupportedAgent.CLAUDE),
      result.mcpRegistrationOutcomes.map { outcome -> outcome.agent }.toSet(),
    )
    assertTrue(
      result.mcpRegistrationOutcomes.all { outcome ->
        outcome.status == McpRegistrationApplyStatus.SKIPPED &&
          outcome.configPath == null &&
          !outcome.changed &&
          outcome.message == "Skipped because install preflight failed."
      },
    )
    assertEquals(sourceBefore, snapshotSource(fixture.repoRoot))
    assertFalse(Files.exists(fixture.home.resolve(".skill-bill/config.json"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(fixture.home.resolve(".codex/config.toml"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(fixture.home.resolve(".claude.json"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(fixture.home.resolve(".skill-bill/installed-skills"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(fixture.home.resolve("agent-skill-targets"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `apply maps native agent source validation failures into structured staging failures`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.writeString(
      fixture.repoRoot.resolve("skills/bill-code-review/native-agents/bill-malformed.md"),
      """
      |---
      |name: bill-malformed
      |---
      |
      |# Missing description
      |
      """.trimMargin(),
    )
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(result.nativeAgents.isEmpty(), "staging failure should stop native-agent apply")
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.STAGING_FAILED &&
          failure.skillName == "bill-code-review" &&
          failure.message.contains("native agent description is required")
      },
    )
  }

  @Test
  fun `apply ignores malformed native agents from unselected platform packs`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.writeString(
      fixture.repoRoot
        .resolve("platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/bill-malformed.md"),
      """
      |---
      |name: bill-malformed
      |---
      |
      |# Missing description in unselected pack
      |
      """.trimMargin(),
    )
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName }
    assertTrue(nativeArtifactNames.any { name -> "bill-kotlin-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-malformed" in name })
  }

  @Test
  fun `apply ignores native agents outside selected planned skill roots`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val legacySkillNativeAgents = fixture.repoRoot.resolve("unselected-native-agents/bill-kmp-legacy/native-agents")
    Files.createDirectories(legacySkillNativeAgents)
    Files.writeString(
      legacySkillNativeAgents.resolve("bill-kmp-legacy-worker.md"),
      """
      |---
      |name: bill-kmp-legacy-worker
      |---
      |
      |# Missing description in unselected legacy skill root
      |
      """.trimMargin(),
    )
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName }
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-legacy-worker" in name })
  }

  @Test
  fun `apply ignores native agents inside selected pack but outside planned skill roots`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val unplannedSkillNativeAgents =
      fixture.repoRoot
        .resolve("platform-packs/kotlin/code-review/bill-kotlin-unplanned/native-agents")
    Files.createDirectories(unplannedSkillNativeAgents)
    Files.writeString(
      unplannedSkillNativeAgents.resolve("bill-kotlin-unplanned-worker.md"),
      """
      |---
      |name: bill-kotlin-unplanned-worker
      |description: Unplanned worker that must be ignored.
      |---
      |
      |# Unplanned selected pack skill worker
      |
      """.trimMargin(),
    )
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName }
    assertFalse(nativeArtifactNames.any { name -> "bill-kotlin-unplanned-worker" in name })
  }

  @Test
  fun `apply native-agent cache root ignores tampered plan staging root`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    val tampered =
      plan.copy(
        staging = plan.staging.copy(root = fixture.home.resolve("outside-installed-skills").toFileLocation()),
      )

    val result = applyInstallForTest(tampered)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    result.nativeAgents
      .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { native -> native.path }
      .forEach { link ->
        val target = readSymlinkTarget(link.toPath())
        assertTrue(target.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
        assertFalse(target.startsWith(fixture.home.resolve("outside-installed-skills")))
      }
  }

  @Test
  fun `apply fails when current source no longer matches planned staging intent`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    Files.writeString(
      fixture.repoRoot.resolve("skills/bill-code-review/content.md"),
      content("bill-code-review").replace("Test body.", "Changed after planning."),
    )
    val sourceBeforeApply = snapshotSource(fixture.repoRoot)

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.STAGING_FAILED &&
          failure.skillName == "bill-code-review" &&
          failure.message.contains("Re-run planInstall")
      },
    )
    assertFalse(
      Files.exists(fixture.home.resolve("agent-skill-targets/codex/bill-code-review"), LinkOption.NOFOLLOW_LINKS),
      "stale plan should not install a link",
    )
    assertTrue(result.nativeAgents.isEmpty(), "stale skill staging should stop native-agent apply")
    assertFalse(
      Files.exists(fixture.home.resolve(".codex/agents/bill-code-review-worker.toml"), LinkOption.NOFOLLOW_LINKS),
      "stale plan should not install native-agent links",
    )
    assertSourceUnchanged(fixture.repoRoot, sourceBeforeApply)
  }

  @Test
  fun `apply rejects unsafe skill link names without escaping target dir`() {
    val fixture = setupApplyFixture()
    seedBaseSkill(fixture.repoRoot, "bill-extra")
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    val unsafePlan =
      plan.copy(
        skills =
          plan.skills.map { skill ->
            if (skill.name == "bill-extra") skill.copy(name = "../victim") else skill
          },
        staging =
          plan.staging.copy(
            skillPaths =
              plan.staging.skillPaths.map { intent ->
                if (intent.skillName == "bill-extra") intent.copy(skillName = "../victim") else intent
              },
          ),
      )

    val result = applyInstallForTest(unsafePlan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.SKILL_LINK_FAILED &&
          failure.skillName == "../victim" &&
          failure.message.contains("safe single path segment")
      },
    )
    assertFalse(
      Files.exists(fixture.home.resolve("agent-skill-targets/victim"), LinkOption.NOFOLLOW_LINKS),
      "unsafe skill name escaped the agent target dir",
    )
  }

  @Test
  fun `apply preserves existing user owned skill target files`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val userFile = targetDir.resolve("bill-code-review")
    Files.writeString(userFile, "user owned")
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals("user owned", Files.readString(userFile))
    assertFalse(Files.isSymbolicLink(userFile), "user-owned file should not be replaced")
    assertTrue(
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
        .any { link ->
          link.agent == SupportedAgent.CODEX &&
            link.status == InstallAgentLinkStatus.FAILED &&
            link.message.contains("preserved")
        },
    )
  }

  @Test
  fun `apply preserves existing user owned skill target symlinks`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val userTarget = Files.createTempFile("skillbill-user-symlink-target", ".md").also(tempDirs::add)
    val userLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(userLink, userTarget)
    val plan =
      planInstallForTest(
        fixture.request(
          agents = setOf(SupportedAgent.CODEX),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals(userTarget.toAbsolutePath().normalize(), readSymlinkTarget(userLink))
    assertTrue(
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
        .any { link ->
          link.agent == SupportedAgent.CODEX &&
            link.status == InstallAgentLinkStatus.FAILED &&
            link.message.contains("preserved")
        },
    )
  }

  @Test
  fun `apply surfaces Windows symlink warning state without parsing shell output`() {
    val fixture = setupApplyFixture()
    val plan =
      planInstallForTest(
        fixture.requestWithWindowsSymlinkPreflight(
          WindowsSymlinkPreflight(
            state = WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
            decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
            message = "Windows symlink support was not confirmed.",
          ),
        ),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(WindowsSymlinkFallbackState.PROCEEDING, result.windowsSymlinkOutcome.fallbackState)
    assertEquals(
      "Windows symlink support was not confirmed.",
      result.windowsSymlinkOutcome.preflight.message,
    )
    assertEquals(
      WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
      result.windowsSymlinkOutcome.preflight.state,
    )
    assertEquals(WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS, result.windowsSymlinkOutcome.preflight.decision)
    assertContains(result.windowsSymlinkOutcome.guidance, "elevated shell")
    assertTrue(result.skills.any { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED })
    assertTrue(
      result.skills
        .flatMap { skill -> skill.links }
        .any { link -> link.status == InstallAgentLinkStatus.CREATED },
    )
    assertTrue(
      result.warnings.any { warning ->
        warning.kind == InstallApplyIssueKind.WINDOWS_SYMLINK_WARNING &&
          warning.message == "Windows symlink support was not confirmed." &&
          warning.guidance.orEmpty().contains("Developer Mode")
      },
    )
  }
}
