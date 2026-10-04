package skillbill.infrastructure.skills.install

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.install.apply.NativeAgentSourceRootsRequest
import skillbill.infrastructure.skills.install.apply.nativeAgentSourceRoots
import skillbill.infrastructure.skills.install.apply.standaloneInstallableSkills
import skillbill.infrastructure.skills.install.plan.InstallContext
import skillbill.infrastructure.skills.install.plan.installSkill
import skillbill.infrastructure.skills.install.staging.InternalSidecarCompanion
import skillbill.infrastructure.skills.install.staging.InternalSidecarTarget
import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.promoteInstallStagingDir
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.install.staging.validateInternalSidecarFileNames
import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InternalSkillStagingCollisionTest : InternalSkillStagingTestSupport() {
  @Test
  fun `selected pack child authored companion installs flat beside its rendered wrapper`() {
    val fixture = setupParentWithInternalPackChild()
    val companionSource = fixture.packChildDir.resolve("compose-guidelines.md")
    Files.writeString(companionSource, "# Compose Guidelines\n\nRequire target-safe UI behavior.\n")
    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) +
        "\nRead [compose-guidelines.md](compose-guidelines.md) for the detailed rubric.\n",
    )

    val rendered =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )

    val wrapper = rendered.stagingDir.resolve("${fixture.packChildName}.md")
    val companion = rendered.stagingDir.resolve("compose-guidelines.md")
    assertTrue(Files.isRegularFile(wrapper.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertEquals(Files.readString(companionSource), Files.readString(companion.toPath()))
    assertTrue(Files.readString(wrapper.toPath()).contains("(compose-guidelines.md)"))
    assertEquals(companion.toPath(), wrapper.toPath().parent.resolve("compose-guidelines.md").normalize())
  }

  @Test
  fun `authored companion bytes invalidate hash and missing companion is restored`() {
    val fixture = setupParentWithInternalPackChild()
    val companionSource = fixture.packChildDir.resolve("review-guidelines.md")
    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) +
        "\nRead [review-guidelines.md](review-guidelines.md) for the governed rubric.\n",
    )
    Files.writeString(companionSource, "first rubric\n")

    val first =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    Files.writeString(companionSource, "second rubric\n")
    val changed =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    assertNotEquals(first.contentHash, changed.contentHash)

    Files.delete(changed.stagingDir.resolve("review-guidelines.md").toPath())
    val restored =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    assertEquals(changed.contentHash, restored.contentHash)
    assertEquals("second rubric\n", Files.readString(restored.stagingDir.resolve("review-guidelines.md").toPath()))
  }

  @Test
  fun `unselected companion is inert and companion collision with wrapper fails loudly`() {
    val fixture = setupParentWithInternalPackChild()
    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) +
        "\nRead [compose-guidelines.md](compose-guidelines.md) for the governed rubric.\n",
    )
    Files.writeString(fixture.packChildDir.resolve("compose-guidelines.md"), "rubric\n")

    val unselected = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertFalse(Files.exists(unselected.stagingDir.resolve("compose-guidelines.md").toPath()))

    Files.delete(fixture.packChildDir.resolve("compose-guidelines.md"))
    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile).substringBefore("\nRead [compose-guidelines.md]") +
        "\nRead [${fixture.packChildName}.md](${fixture.packChildName}.md) for the governed rubric.\n",
    )
    Files.writeString(fixture.packChildDir.resolve("${fixture.packChildName}.md"), "collision\n")
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        stageInstalledSkill(
          StageInstalledSkillInput(
            repoRoot = fixture.repoRoot,
            sourceSkillDir = fixture.parentDir,
            home = fixture.home,
            selectedPackSkills = listOf(fixture.packChildPlanSkill),
          ),
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INTERNAL_SKILL_SIDECAR_COLLISION, failure.code)
      }
    assertContains(error.message.orEmpty(), "sidecar '${fixture.packChildName}.md'")
  }

  @Test
  fun `authored companion must be one linked non-reserved rubric`() {
    val fixture = setupParentWithInternalPackChild()
    Files.writeString(fixture.packChildDir.resolve("patterns.md"), "organization notes\n")

    assertFailsWith<SkillBillRuntimeException> {
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_AUTHORED_SKILL_SIDECAR, failure.code)
    }

    Files.delete(fixture.packChildDir.resolve("patterns.md"))
    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) +
        "\nRead [Review-Orchestrator.md](Review-Orchestrator.md) for the governed rubric.\n",
    )
    Files.writeString(fixture.packChildDir.resolve("Review-Orchestrator.md"), "override\n")
    assertFailsWith<SkillBillRuntimeException> {
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_AUTHORED_SKILL_SIDECAR, failure.code)
    }
  }

  @Test
  fun `portable collision validation rejects companions claimed by two children`() {
    val parent = Files.createTempDirectory("skillbill-sidecar-collision-parent").also(tempDirs::add)
    val first =
      InternalSidecarTarget(
        skillName = "bill-first",
        sourceDir = parent,
        renderedWrapper = "first",
        authoredCompanions = listOf(InternalSidecarCompanion("Rubric.md", byteArrayOf(1))),
      )
    val second =
      InternalSidecarTarget(
        skillName = "bill-second",
        sourceDir = parent,
        renderedWrapper = "second",
        authoredCompanions = listOf(InternalSidecarCompanion("rubric.md", byteArrayOf(2))),
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSidecarFileNames(parent, listOf(first, second))
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INTERNAL_SKILL_SIDECAR_COLLISION, failure.code)
      }

    assertContains(error.message.orEmpty(), "sidecar 'rubric.md'")
  }

  @Test
  fun `failed replacement restores the previous staging directory`() {
    val root = Files.createTempDirectory("skillbill-staging-restore").also(tempDirs::add)
    val finalDir = root.resolve("bill-feature-hash")
    Files.createDirectories(finalDir)
    Files.writeString(finalDir.resolve("SKILL.md"), "healthy\n")

    assertFailsWith<IOException> {
      promoteInstallStagingDir(root.resolve("missing-temp"), finalDir)
    }

    assertEquals("healthy\n", Files.readString(finalDir.resolve("SKILL.md")))
    assertTrue(Files.list(root).use { paths -> paths.noneMatch { it.fileName.toString().contains(".backup-") } })
  }

  @Test
  fun `pack-aware staging is byte-identical to pre-change when no skill opts in`() {
    val fixture = setupParentWithInternalPackChild()

    val inert = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertTrue(inert.renderedSidecarFiles.isEmpty(), "inert staging must carry no sidecars")

    val inertExplicitEmpty =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = emptyList(),
        ),
      )
    assertEquals(
      inert.contentHash,
      inertExplicitEmpty.contentHash,
      "an explicit empty pack-skill list must not change the hash",
    )
    assertEquals(inert.stagingDir, inertExplicitEmpty.stagingDir)
  }

  @Test
  fun `standaloneInstallableSkills excludes internal pack skills that nativeAgentSourceRoots retains`() {
    val parent = planSkill("bill-code-review", internalFor = null)
    val packInternal =
      planSkill(
        "bill-kotlin-code-review",
        internalFor = "bill-code-review",
        platformSlug = "kotlin",
      )
    val skills = listOf(parent, packInternal)

    val standalone = standaloneInstallableSkills(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertEquals(
      listOf("bill-code-review"),
      standalone.map { it.name },
      "an internal pack skill must not stage standalone or link into skills_dir",
    )

    val sourceRoots =
      nativeAgentSourceRoots(
        NativeAgentSourceRootsRequest(skills = skills, selectedPlatformSlugs = setOf("kotlin")),
      )
    assertTrue(
      packInternal.sourceDir.toPath() in sourceRoots,
      "an internal pack skill's dir must remain a native-agent source root (PD6 parity)",
    )
  }

  @Test
  fun `installSkill refuses to link an internal pack skill directly`() {
    val fixture = setupParentWithInternalPackChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        installSkill(
          skillPath = fixture.packChildDir,
          agentTargets = listOf(AgentTarget("test-agent", agentRoot.toFileLocation())),
          context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("internal-for: ${fixture.parentName}"))
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.packChildName), LinkOption.NOFOLLOW_LINKS),
      "refused pack link must leave no skills_dir entry",
    )
  }

  @Test
  fun `repo validation accepts a healthy pack internal child classification`() {
    val fixture = setupParentWithInternalPackChild()

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    val internalIssues =
      report.issues.filter { issue ->
        issue.contains("internal-for") || issue.contains("internal skill") || issue.contains("platform-pack skill")
      }
    assertTrue(
      internalIssues.isEmpty(),
      "healthy pack internal classification must raise no internal-skill issue, got: $internalIssues",
    )
  }

  @Test
  fun `repo validation rejects a pack child declaring an unknown parent`() {
    val fixture = setupParentWithInternalPackChild()
    Files.writeString(
      fixture.packChildContentFile,
      """
      ---
      name: ${fixture.packChildName}
      description: Internal pack.
      internal-for: bill-no-such-parent
      ---

      Body.
      """.trimIndent(),
    )

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains(fixture.packChildName) },
      "validate must surface the unknown-parent rule for a pack skill; issues=${report.issues}",
    )
  }
}
