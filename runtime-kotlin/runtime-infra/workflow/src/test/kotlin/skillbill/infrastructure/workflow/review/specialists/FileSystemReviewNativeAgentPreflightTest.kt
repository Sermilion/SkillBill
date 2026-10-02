package skillbill.infrastructure.workflow.review.specialists

import skillbill.error.shellcontent.MissingInstalledNativeAgentError
import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator
import skillbill.infrastructure.host.FileTelemetryConfigStore
import skillbill.infrastructure.skills.install.FileSystemInstalledPlatformPackCatalog
import skillbill.infrastructure.skills.install.apply.currentNativeAgentApplyCacheRoot
import skillbill.infrastructure.skills.install.mcp.McpRegistrationOperations
import skillbill.infrastructure.skills.install.runtime.InstallOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallApplyResult
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.EnvironmentContext
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.review.model.ReviewNativeAgentPreflightRequest
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val INLINE_WORKER = "bill-code-review-inline"

class FileSystemReviewNativeAgentPreflightTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
      }
    }
  }

  @Test
  fun `preflight rejects stale Codex inventory when provider root disappeared`() {
    val fixture = setupInstallFixture()
    val cacheRoot = currentNativeAgentApplyCacheRoot(fixture.home, fixture.packsRoot, fixture.skillsRoot)
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    Files.writeString(
      inventory,
      codexInventoryJson(
        logicalName = "bill-code-review-worker",
        installedPath = fixture.home.resolve(".agents/agents/bill-code-review-worker.toml"),
        cacheTargetPath = cacheRoot.resolve("codex-agents/bill-code-review-worker.toml"),
        sourceRoot = fixture.repoRoot,
      ),
    )

    val error =
      assertFailsWith<MissingInstalledNativeAgentError> {
        preflight(fixture.home).verify(preflightRequest(fixture.repoRoot, "codex"))
      }

    assertTrue(error.message.orEmpty().contains("active provider directory is missing"))
    assertEquals("skill-bill install apply", error.repairCommand)
  }

  @Test
  fun `preflight accepts the current installed-skills native-agent generation`() {
    val fixture = setupInstallFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    assertEquals(InstallApplyStatus.SUCCESS, install(fixture, SupportedAgent.CODEX).status)

    preflight(fixture.home).verify(preflightRequest(fixture.repoRoot, "codex"))
  }

  @Test
  fun `cursor preflight fails with the repair command when a managed link is deleted`() {
    val fixture = setupInstallFixture()
    Files.createDirectories(fixture.home.resolve(".cursor"))
    assertEquals(InstallApplyStatus.SUCCESS, install(fixture, SupportedAgent.CURSOR).status)
    val installed =
      fixture.home.resolve(".cursor/agents")
        .resolve(NativeAgentProvider.Cursor.fileName("bill-code-review-worker"))
    Files.delete(installed)

    val failure =
      assertFailsWith<MissingInstalledNativeAgentError> {
        preflight(fixture.home).verify(preflightRequest(fixture.repoRoot, "cursor"))
      }

    assertContains(failure.message.orEmpty(), "skill-bill install apply")
  }

  @Test
  fun `preflight accepts installed native agents after source checkout is removed`() {
    val fixture = setupInstallFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    assertEquals(InstallApplyStatus.SUCCESS, install(fixture, SupportedAgent.CODEX).status)
    Files.walk(fixture.repoRoot).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    val reviewedRepo = Files.createTempDirectory("skillbill-reviewed-repo").also(tempDirs::add)

    preflight(fixture.home).verify(preflightRequest(reviewedRepo, "codex"))
  }

  @Test
  fun `review planning resolves installed rubrics and inline worker from skill-bill sidecars`() {
    val fixture = setupInstallFixture()
    val inlineWorkerDir = fixture.skillsRoot.resolve(INLINE_WORKER)
    Files.createDirectories(inlineWorkerDir)
    Files.writeString(
      inlineWorkerDir.resolve("content.md"),
      "---\ninternal-for: $PACK_SIDECAR_PARENT_SKILL\nname: $INLINE_WORKER\ndescription: Inline worker.\n---\n\n" +
        "Review inline.\n",
    )
    seedNativeAgent(inlineWorkerDir, INLINE_WORKER)
    Files.createDirectories(fixture.home.resolve(".codex"))
    assertEquals(InstallApplyStatus.SUCCESS, install(fixture, SupportedAgent.CODEX).status)

    val skillTargets = fixture.home.resolve("agent-skill-targets/codex")
    val installedParent = skillTargets.resolve(PACK_SIDECAR_PARENT_SKILL)
    val sidecars =
      listOf("$INLINE_WORKER.md", "bill-kotlin-code-review.md", "bill-kotlin-code-review-architecture.md")
    sidecars.forEach { sidecar ->
      assertTrue(Files.isRegularFile(installedParent.resolve(sidecar)), "missing $sidecar under installed skill-bill")
    }
    val legacyParent = skillTargets.resolve("bill-code-review")
    sidecars.forEach { sidecar ->
      assertFalse(Files.exists(legacyParent.resolve(sidecar)), "$sidecar must not stage under bill-code-review")
    }
    Files.deleteIfExists(legacyParent)
    Files.walk(fixture.repoRoot).use { paths ->
      paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val environment = EnvironmentContext(userHome = fixture.home, environment = testEnvironment(fixture.home))
    val kotlin = FileSystemInstalledPlatformPackCatalog(environment).manifests().single { it.slug == "kotlin" }
    val rubric = FileSystemReviewRubricResolver().resolve(kotlin)
    assertEquals("bill-kotlin-code-review", rubric.rubricId)
    assertEquals(listOf("bill-kotlin-code-review-architecture"), rubric.specialists.map { it.rubricId })

    preflight(fixture.home).verify(
      ReviewNativeAgentPreflightRequest(
        repoRoot = Files.createTempDirectory("skillbill-reviewed-repo").also(tempDirs::add),
        agentIds = listOf("codex"),
        logicalNames = listOf(INLINE_WORKER),
      ),
    )
  }

  private data class InstallFixture(
    val repoRoot: Path,
    val home: Path,
  ) {
    val skillsRoot: Path get() = repoRoot.resolve("skills")
    val packsRoot: Path get() = repoRoot.resolve("platform-packs")
  }

  private fun setupInstallFixture(): InstallFixture {
    val repoRoot = Files.createTempDirectory("skillbill-preflight-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-preflight-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, PACK_SIDECAR_PARENT_SKILL)
    seedBaseSkill(repoRoot, "bill-code-review", nativeAgentName = "bill-code-review-worker")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedBaseSkill(repoRoot, "bill-update-check")
    listOf("kotlin", "kmp").forEach { slug ->
      seedConformingPlatformPack(repoRoot, slug)
      seedNativeAgent(
        repoRoot.resolve("platform-packs/$slug/code-review/bill-$slug-code-review"),
        "bill-$slug-code-review-worker",
      )
    }
    return InstallFixture(repoRoot, home)
  }

  private fun seedBaseSkill(
    repoRoot: Path,
    name: String,
    nativeAgentName: String? = null,
  ) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: $name\ndescription: Test skill.\n---\n\nTest body.\n",
    )
    nativeAgentName?.let { seedNativeAgent(skillDir, it) }
  }

  private fun seedNativeAgent(
    skillDir: Path,
    name: String,
  ) {
    val nativeAgentDir = skillDir.resolve("native-agents")
    Files.createDirectories(nativeAgentDir)
    Files.writeString(
      nativeAgentDir.resolve("$name.md"),
      "---\nname: $name\ndescription: Test native agent.\n---\n\n# $name\n\nDo the work.\n",
    )
  }

  private fun install(
    fixture: InstallFixture,
    agent: SupportedAgent,
  ): InstallApplyResult {
    val environment = testEnvironment(fixture.home)
    val plan = InstallOperations.planInstall(installRequest(fixture, agent, environment), InstallPlanSchemaValidator())
    return InstallOperations.applyInstall(
      plan,
      telemetryConfigStore =
        FileTelemetryConfigStore(EnvironmentContext(userHome = fixture.home, environment = environment)),
      mcpRegistrationPort = TestMcpRegistrationPort(environment),
    )
  }

  private fun installRequest(
    fixture: InstallFixture,
    agent: SupportedAgent,
    environment: Map<String, String>,
  ): InstallPlanRequest =
    InstallPlanRequest(
      repoRoot = fixture.repoRoot.toFileLocation(),
      home = fixture.home.toFileLocation(),
      agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.MANUAL, manualAgents = setOf(agent)),
      platformPackSelection =
        PlatformPackSelection(mode = PlatformPackSelectionMode.SELECTED, selectedSlugs = setOf("kotlin")),
      telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
      mcpRegistrationChoice =
        McpRegistrationChoice(
          register = true,
          runtimeMcpBin = fixture.home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp").toFileLocation(),
        ),
      runtimeDistributionInputs =
        RuntimeDistributionInputs(runtimeInstallRoot = fixture.home.resolve(".skill-bill/runtime").toFileLocation()),
      targetPaths =
        InstallationTargetPaths(
          skillsRoot = fixture.skillsRoot.toFileLocation(),
          platformPacksRoot = fixture.packsRoot.toFileLocation(),
          agentTargets =
            listOf(
              InstallAgentTarget(
                agent = agent,
                path = fixture.home.resolve("agent-skill-targets/${agent.id}").toFileLocation(),
                source = InstallAgentTargetSource.MANUAL,
              ),
            ),
        ),
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      replaceExistingSkillBillLinks = false,
      environment = environment,
    )

  private class TestMcpRegistrationPort(
    private val environment: Map<String, String>,
  ) : InstallMcpRegistrationPort {
    override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
      InstallMcpRegistrationResult(
        outcome = McpRegistrationOperations.register(request.agent, request.runtimeMcpBin, request.home, environment),
      )

    override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
      InstallMcpRegistrationResult(
        outcome = McpRegistrationOperations.unregister(request.agent, request.home, environment),
      )
  }

  private fun preflight(home: Path): FileSystemReviewNativeAgentPreflight =
    FileSystemReviewNativeAgentPreflight(EnvironmentContext(userHome = home, environment = testEnvironment(home)))

  private fun preflightRequest(
    repoRoot: Path,
    agentId: String,
  ): ReviewNativeAgentPreflightRequest =
    ReviewNativeAgentPreflightRequest(
      repoRoot = repoRoot,
      agentIds = listOf(agentId),
      logicalNames = listOf("bill-code-review-worker"),
    )

  private fun testEnvironment(home: Path): Map<String, String> {
    val environment = mutableMapOf("HOME" to home.toString())
    val codexHome = home.resolve(".codex")
    if (Files.isDirectory(codexHome)) environment["CODEX_HOME"] = codexHome.toString()
    val claudeHome = home.resolve(".claude")
    if (Files.isDirectory(claudeHome)) environment["CLAUDE_CONFIG_DIR"] = claudeHome.toString()
    return environment
  }

  private fun codexInventoryJson(
    logicalName: String,
    installedPath: Path,
    cacheTargetPath: Path,
    sourceRoot: Path,
  ): String =
    """
    {"contract_version":"0.2","entries":[
      {"logical_name":"$logicalName","provider":"codex","installed_path":"$installedPath",
        "cache_target_path":"$cacheTargetPath","content_digest":"${"0".repeat(64)}","source_root":"$sourceRoot"}
    ]}
    """.trimIndent()
}
