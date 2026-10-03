package skillbill.infrastructure.skills.install

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.InvalidInstallPlanSchemaError
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.authoredFilesFor
import skillbill.infrastructure.skills.install.staging.content.computeInstallContentHash
import skillbill.infrastructure.skills.install.staging.generatedSupportPointersFor
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.SupportedAgent
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation

class InstallPlanBuilderTest : InstallPlanBuilderTestSupport() {
  @Test
  fun `review capable plan includes the manifest declared fallback without slug knowledge`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, slug = "team-default", areaNames = listOf("security"))
    declareCodeReviewFallback(fixture.repoRoot, "team-default")

    val plan = planInstallForTest(fixture.request())

    assertEquals(listOf("team-default"), plan.selectedPlatformSlugs)
    assertTrue(plan.skills.any { it.name == "bill-team-default-code-review" })
    assertTrue(plan.skills.any { it.name == "bill-team-default-code-review-security" })
  }

  @Test
  fun `review capable plan retains horizontal base behavior when no fallback is declared`() {
    val fixture = setupPlanFixture()

    val plan = planInstallForTest(fixture.request())

    assertEquals(emptyList(), plan.selectedPlatformSlugs)
    assertTrue(plan.skills.any { it.name == "bill-code-review" && it.kind == InstallPlanSkillKind.BASE })
    assertFalse(plan.skills.any { it.kind == InstallPlanSkillKind.PLATFORM_PACK })
  }

  @Test
  fun `review capable plan rejects multiple manifest declared fallbacks`() {
    val fixture = setupPlanFixture()
    listOf("first-default", "second-default").forEach { slug ->
      seedPlatformPack(fixture.repoRoot, slug = slug)
      declareCodeReviewFallback(fixture.repoRoot, slug)
    }

    assertFailsWith<SkillBillRuntimeException> {
      planInstallForTest(fixture.request())
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, failure.code)
    }
  }

  @Test
  fun `plan includes base skills and dynamically selected platform pack skills`() {
    val fixture = setupPlanFixture()

    val plan =
      planInstallForTest(
        fixture.request(
          platformPackSelection =
            PlatformPackSelection(
              mode = PlatformPackSelectionMode.SELECTED,
              selectedSlugs = setOf("kotlin"),
            ),
        ),
      )

    val skillsByName = plan.skills.associateBy { skill -> skill.name }
    assertEquals(
      listOf("kmp", "kotlin"),
      plan.discoveredPlatformPacks.map { pack -> pack.slug },
    )
    assertEquals(listOf("kotlin"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-update-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review-architecture").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review-testing").kind)
    assertFalse(skillsByName.containsKey("bill-kotlin-code-check"))
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"))
    assertFalse(skillsByName.containsKey("bill-kmp-code-review-architecture"))
  }

  @Test
  fun `selected platform planning is driven by newly discovered pack manifests with base skills included`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, slug = "python", areaNames = listOf("security"))

    val plan =
      planInstallForTest(
        fixture.request(
          platformPackSelection =
            PlatformPackSelection(
              mode = PlatformPackSelectionMode.SELECTED,
              selectedSlugs = setOf("python"),
            ),
        ),
      )

    assertEquals(listOf("kmp", "kotlin", "python"), plan.discoveredPlatformPacks.map { pack -> pack.slug })
    assertEquals(listOf("python"), plan.selectedPlatformSlugs)
    val skillsByName = plan.skills.associateBy { skill -> skill.name }
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-check").kind)
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-update-check").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-python-code-review-security").kind)
    assertFalse(skillsByName.containsKey("bill-python-code-check"))
    assertFalse(skillsByName.containsKey("bill-kotlin-code-review"))
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"))
  }

  @Test
  fun `selected platform planning rejects malformed specialist structure`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(fixture.repoRoot, slug = "invalid-review-shape")
    Files.writeString(
      fixture.repoRoot.resolve(
        "platform-packs/invalid-review-shape/code-review/" +
          "bill-invalid-review-shape-code-review-architecture/content.md",
      ),
      """
      |---
      |name: bill-invalid-review-shape-code-review-architecture
      |description: Malformed architecture specialist fixture.
      |internal-for: skill-bill
      |---
      |
      |# Malformed Architecture Specialist
      |
      |## Focus
      |
      |Missing the governed specialist skeleton.
      |
      """.trimMargin(),
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("invalid-review-shape"),
              ),
          ),
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_REVIEW_SKILL_STRUCTURE, failure.code)
      }

    assertContains(error.message.orEmpty(), "specialist H2 sequence")
  }

  @Test
  fun `manual agent selection resolves target paths and MCP intent without executing registration`() {
    val fixture = setupPlanFixture()
    val claudeTarget = fixture.home.resolve("manual-claude")
    val beforeHome = snapshotTree(fixture.home)

    val plan =
      planInstallForTest(
        fixture.request(
          agentSelection =
            InstallAgentSelection(
              mode = InstallAgentSelectionMode.MANUAL,
              manualAgents = setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX),
            ),
          targetPaths =
            fixture.targetPaths(
              agentTargets =
                listOf(
                  InstallAgentTarget(
                    agent = SupportedAgent.CLAUDE,
                    path = claudeTarget.toFileLocation(),
                    source = InstallAgentTargetSource.MANUAL,
                  ),
                ),
            ),
        ),
      )

    assertEquals(listOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX), plan.agents.map { target -> target.agent })
    assertEquals(claudeTarget, plan.agents.first { target -> target.agent == SupportedAgent.CLAUDE }.path.toPath())
    assertEquals(
      fixture.home.resolve(".agents/skills"),
      plan.agents.first { it.agent == SupportedAgent.CODEX }.path.toPath(),
    )
    assertEquals(listOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX), plan.mcpRegistrationIntent.agents)
    assertEquals(fixture.runtimeMcpBin, plan.mcpRegistrationIntent.runtimeMcpBin?.toPath())
    assertTrue(plan.mcpRegistrationIntent.register)
    assertEquals(beforeHome, snapshotTree(fixture.home), "planning must not mutate the home tree")
  }

  @Test
  fun `detection derived agent selection uses existing detection without unsupported agents`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))

    val plan =
      planInstallForTest(
        fixture.request(
          agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
        ),
      )

    assertEquals(listOf(SupportedAgent.CODEX, SupportedAgent.CODEX), plan.agents.map { target -> target.agent })
    assertEquals(
      listOf(InstallAgentTargetSource.DETECTED, InstallAgentTargetSource.DETECTED),
      plan.agents.map { target -> target.source },
    )
    assertEquals(
      listOf(
        fixture.home.resolve(".codex/skills"),
        fixture.home.resolve(".agents/skills"),
      ),
      plan.agents.map { target -> target.path.toPath() },
    )
  }

  @Test
  fun `detection derived agent selection preserves caller supplied detected targets`() {
    val fixture = setupPlanFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val detectedTarget =
      InstallAgentTarget(
        agent = SupportedAgent.CLAUDE,
        path = fixture.home.resolve("detected-claude").toFileLocation(),
        source = InstallAgentTargetSource.MANUAL,
      )

    val plan =
      planInstallForTest(
        fixture.request(
          agentSelection =
            InstallAgentSelection(
              mode = InstallAgentSelectionMode.DETECTED,
              detectedTargets = listOf(detectedTarget),
            ),
        ),
      )

    assertEquals(listOf(SupportedAgent.CLAUDE), plan.agents.map { target -> target.agent })
    assertEquals(listOf(InstallAgentTargetSource.DETECTED), plan.agents.map { target -> target.source })
    assertEquals(fixture.home.resolve("detected-claude"), plan.agents.single().path.toPath())
    assertEquals(1, plan.agents.size)
  }
}

class InstallPlanBuilderPlatformSelectionTest : InstallPlanBuilderTestSupport() {
  @Test
  fun `all platform selection selects every discovered pack`() {
    val fixture = setupPlanFixture()

    val plan =
      planInstallForTest(
        fixture.request(
          platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
        ),
      )

    assertEquals(listOf("kmp", "kotlin"), plan.selectedPlatformSlugs)
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review-architecture" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kmp-code-review-testing" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review-architecture" })
    assertTrue(plan.skills.any { skill -> skill.name == "bill-kotlin-code-review-testing" })
  }

  @Test
  fun `unknown selected platform slugs fail during planning`() {
    val fixture = setupPlanFixture()

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("swift"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "Unknown platform pack selection: swift")
    assertContains(error.message.orEmpty(), "Discovered platform packs: kmp, kotlin")
  }

  @Test
  fun `platform pack discovery validates selected pack contract and content`() {
    val badVersion = setupPlanFixture()
    val badVersionManifest = badVersion.repoRoot.resolve("platform-packs/kotlin/platform.yaml")
    Files.writeString(
      badVersionManifest,
      Files.readString(badVersionManifest).replace("contract_version: \"1.8\"", "contract_version: \"9.9\""),
    )

    val versionError =
      assertFailsWith<ContractVersionMismatchError> {
        planInstallForTest(
          badVersion.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
        )
      }
    assertContains(versionError.message.orEmpty(), "contract_version '9.9'")

    val missingContent = setupPlanFixture()
    Files.delete(
      missingContent.repoRoot
        .resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture/content.md"),
    )

    val contentError =
      assertFailsWith<SkillBillRuntimeException> {
        planInstallForTest(
          missingContent.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.MISSING_CONTENT_FILE, failure.code)
      }
    assertContains(contentError.message.orEmpty(), "bill-kotlin-code-review-architecture/content.md")
  }

  @Test
  fun `selected platform declared content files cannot escape owning pack root`() {
    val fixture = setupPlanFixture()
    val outsideSkillDir = fixture.repoRoot.resolve("outside/bill-escaped-code-review")
    Files.createDirectories(outsideSkillDir)
    Files.writeString(outsideSkillDir.resolve("content.md"), content("bill-escaped-code-review"))
    val manifest = fixture.repoRoot.resolve("platform-packs/kotlin/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "baseline: \"code-review/bill-kotlin-code-review/content.md\"",
        "baseline: \"../../outside/bill-escaped-code-review/content.md\"",
      ),
    )

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("kotlin"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "Platform pack 'kotlin' declared content file")
    assertContains(error.message.orEmpty(), "escapes packRoot")
  }

  @Test
  fun `duplicate planned skill names fail before staging intent is produced`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "duplicate",
      areaNames = listOf("architecture"),
    )
    val duplicateManifest = fixture.repoRoot.resolve("platform-packs/duplicate/platform.yaml")
    Files.writeString(
      duplicateManifest,
      Files.readString(duplicateManifest).replace(
        "architecture: \"code-review/bill-duplicate-code-review-architecture/content.md\"",
        "architecture: \"code-review/bill-duplicate-code-review/content.md\"",
      ),
    )

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("duplicate"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "duplicate skill name")
    assertContains(error.message.orEmpty(), "bill-duplicate-code-review")
  }

  @Test
  fun `duplicate platform manifest slots pointing to one skill directory fail during planning`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "collapsed",
      areaNames = emptyList(),
    )
    val manifest = fixture.repoRoot.resolve("platform-packs/collapsed/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        "architecture: \"code-review/bill-collapsed-code-review-architecture/content.md\"",
        "architecture: \"code-review/bill-collapsed-code-review/content.md\"",
      ),
    )

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("collapsed"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "duplicate skill name")
    assertContains(error.message.orEmpty(), "bill-collapsed-code-review")
  }

  @Test
  fun `missing base skills root fails instead of producing a plan without base skills`() {
    val fixture = setupPlanFixture()

    val error =
      assertFailsWith<FileNotFoundException> {
        planInstallForTest(
          fixture.request(
            targetPaths =
              fixture.targetPaths().copy(
                skillsRoot = fixture.repoRoot.resolve("missing-skills").toFileLocation(),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "Base skills root")
  }

  @Test
  fun `base skill directories without content fail even when another base skill is valid`() {
    val fixture = setupPlanFixture()
    Files.delete(fixture.repoRoot.resolve("skills/bill-code-review/content.md"))

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(fixture.request())
      }

    assertContains(error.message.orEmpty(), "without content.md")
    assertContains(error.message.orEmpty(), "bill-code-review")
  }

  @Test
  fun `existing base skills root with no valid bill skills fails during planning`() {
    val fixture = setupPlanFixture()
    val emptySkillsRoot = fixture.repoRoot.resolve("empty-skills")
    Files.createDirectories(emptySkillsRoot.resolve("notes"))

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            targetPaths = fixture.targetPaths().copy(skillsRoot = emptySkillsRoot.toFileLocation()),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "does not contain any bill-* skills with content.md")
  }

  @Test
  fun `base support pointers use requested skills root rather than repo root skills`() {
    val fixture = setupPlanFixture()
    val packagedSkillsRoot = fixture.repoRoot.resolve("packaged-skills")
    seedBaseSkillAt(packagedSkillsRoot, "bill-code-review")
    seedSupportTarget(fixture.repoRoot, "orchestration/shell-content-contract/shell-ceremony.md")
    seedSkillClass(fixture.repoRoot, "bill-code-review", listOf("shell-ceremony"))

    val plan =
      planInstallForTest(
        fixture.request(
          targetPaths = fixture.targetPaths().copy(skillsRoot = packagedSkillsRoot.toFileLocation()),
        ),
      )

    val sourceDir = packagedSkillsRoot.resolve("bill-code-review").toAbsolutePath().normalize()
    val supportPointers =
      generatedSupportPointersFor(
        repoRoot = fixture.repoRoot,
        sourceSkillDir = sourceDir,
        skillName = "bill-code-review",
        skillsRoot = packagedSkillsRoot,
      )
    val authored = authoredFilesFor(sourceDir, applicablePointers(fixture.repoRoot, sourceDir), supportPointers)
    val expectedHash =
      computeInstallContentHash(
        sourceSkillDir = sourceDir,
        authored = authored,
        applicablePointers = emptyList(),
        generatedSupportPointers = supportPointers,
      )

    assertEquals(listOf("shell-ceremony.md"), supportPointers.map { pointer -> pointer.name })
    assertEquals(
      expectedHash,
      plan.staging.skillPaths.single { path -> path.skillName == "bill-code-review" }.contentHash,
    )
  }

  @Test
  fun `planning fails when selected platform pointer target is missing`() {
    val fixture = setupPlanFixture()
    seedPlatformPack(
      fixture.repoRoot,
      slug = "pointer-missing",
      areaNames = emptyList(),
      pointerTarget = "orchestration/missing/PLAYBOOK.md",
    )

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("pointer-missing"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "review-orchestrator.md")
    assertContains(error.message.orEmpty(), "does not exist")
  }

  @Test
  fun `planning fails when selected platform pointer target escapes through symlinked parent`() {
    val fixture = setupPlanFixture()
    val outsideRoot = Files.createTempDirectory("skillbill-install-plan-pointer-target").also(tempDirs::add)
    Files.writeString(outsideRoot.resolve("PLAYBOOK.md"), "# Outside target\n")
    Files.createSymbolicLink(fixture.repoRoot.resolve("orchestration"), outsideRoot)
    seedPlatformPack(
      fixture.repoRoot,
      slug = "pointer-parent-symlink",
      areaNames = emptyList(),
      pointerTarget = "orchestration/PLAYBOOK.md",
    )

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(
          fixture.request(
            platformPackSelection =
              PlatformPackSelection(
                mode = PlatformPackSelectionMode.SELECTED,
                selectedSlugs = setOf("pointer-parent-symlink"),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "review-orchestrator.md")
    assertContains(error.message.orEmpty(), "escapes repoRoot")
  }

  @Test
  fun `planning fails when generated support pointer target is missing`() {
    val fixture = setupPlanFixture()
    seedSkillClass(fixture.repoRoot, "bill-code-review", listOf("shell-ceremony"))

    val error =
      assertFailsWith<IllegalArgumentException> {
        planInstallForTest(fixture.request())
      }

    assertContains(error.message.orEmpty(), "Supporting pointer 'shell-ceremony.md'")
    assertContains(error.message.orEmpty(), "does not exist")
  }

  @Test
  fun `staging runtime telemetry and windows intent are represented without applying`() {
    val fixture = setupPlanFixture()
    val before = snapshotTree(fixture.repoRoot)

    val plan =
      planInstallForTest(
        fixture.request(
          telemetryLevel = InstallTelemetryLevel.FULL,
          windowsSymlinkPreflight =
            WindowsSymlinkPreflight(
              state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
              decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
              message = "Windows requires elevation or Developer Mode before symlink install.",
            ),
        ),
      )

    assertEquals(fixture.home.resolve(".skill-bill/installed-skills"), plan.staging.root.toPath())
    assertTrue(plan.staging.skillPaths.isNotEmpty())
    assertTrue(plan.staging.skillPaths.all { path -> path.stagingDir.startsWith(plan.staging.root) })
    assertEquals(InstallTelemetryLevel.FULL, plan.telemetryLevel)
    assertEquals(fixture.runtimeInstallRoot, plan.runtimeDistributionInputs.runtimeInstallRoot.toPath())
    assertEquals(WindowsSymlinkPreflightState.DECISION_REQUIRED, plan.windowsSymlinkPreflight.state)
    assertEquals(WindowsSymlinkDecision.REQUIRE_USER_ACTION, plan.windowsSymlinkPreflight.decision)
    assertEquals(
      "Windows requires elevation or Developer Mode before symlink install.",
      plan.windowsSymlinkPreflight.message,
    )
    assertEquals(before, snapshotTree(fixture.repoRoot), "planning must not write generated artifacts into source")
  }

  @Test
  fun `builder seam still validates install plan wire map with typed schema error`() {
    val fixture = setupPlanFixture()

    val error =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        planInstallForTest(
          fixture.request().copy(
            mcpRegistrationChoice =
              McpRegistrationChoice(
                register = true,
                runtimeMcpBin = Path.of("").toFileLocation(),
              ),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }
}
