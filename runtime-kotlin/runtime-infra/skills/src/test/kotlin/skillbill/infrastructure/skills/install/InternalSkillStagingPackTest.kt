package skillbill.infrastructure.skills.install

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.install.plan.InstallContext
import skillbill.infrastructure.skills.install.plan.installSkill
import skillbill.infrastructure.skills.install.plan.uninstallTargets
import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InternalSkillStagingPackTest : InternalSkillStagingTestSupport() {
  @Test
  fun `installSkill refuses to link an internal skill directly`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        installSkill(
          skillPath = fixture.childDir,
          agentTargets = listOf(AgentTarget("test-agent", agentRoot.toFileLocation())),
          context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("internal-for: ${fixture.parentName}"))
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.childName), LinkOption.NOFOLLOW_LINKS),
      "refused link must leave no skills_dir entry",
    )
  }

  @Test
  fun `uninstallTargets removes the parent link and repeats as a no-op`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val links =
      installSkill(
        skillPath = fixture.parentDir,
        agentTargets = listOf(AgentTarget("test-agent", agentRoot.toFileLocation())),
        context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
      ).linkPaths
    val parentLink = links.single()
    assertTrue(Files.isSymbolicLink(parentLink))

    val removed = uninstallTargets(links)
    assertEquals(links, removed)
    assertFalse(Files.exists(parentLink, LinkOption.NOFOLLOW_LINKS))

    val removedAgain = uninstallTargets(links)
    assertTrue(removedAgain.isEmpty(), "repeat uninstall must be a no-op, got $removedAgain")
  }

  @Test
  fun `repo validation rejects unknown internal parent at validate time`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-validate-unknown").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature-helper", "bill-feature-helper", "Internal.")
    Files.writeString(
      repoRoot.resolve("skills/bill-feature-helper/content.md"),
      """
      ---
      name: bill-feature-helper
      description: Internal.
      internal-for: bill-featur
      ---

      Body.
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains("bill-featur") },
      "validate must surface the unknown-parent rule; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation rejects a blank internal-for value read from content md`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-validate-blank").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature-helper", "bill-feature-helper", "Internal.")
    Files.writeString(
      repoRoot.resolve("skills/bill-feature-helper/content.md"),
      """
      ---
      name: bill-feature-helper
      description: Internal.
      internal-for:
      ---

      Body.
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("empty value") && it.contains("bill-feature-helper") },
      "a blank internal-for must fail loudly, not degrade to listed; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation raises no internal-skill issue for a healthy classification`() {
    val fixture = setupParentWithInternalChild()

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    val internalIssues =
      report.issues.filter { issue ->
        issue.contains("internal-for") || issue.contains("internal skill") || issue.contains("not a discovered skill")
      }
    assertTrue(
      internalIssues.isEmpty(),
      "healthy internal classification must raise no internal-skill issue, got: $internalIssues",
    )
  }

  @Test
  fun `repo validation rejects a sidecar reference to another parent's internal child`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(
      fixture.repoRoot,
      "bill-outsider",
      "bill-outsider",
      "Listed skill referencing a foreign sidecar.",
      body = "Read the file `${fixture.childName}.md` and execute it.",
    )

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    assertTrue(
      report.issues.any { it.contains("bill-outsider") && it.contains("not co-located") },
      "a sidecar reference outside the parent's directory must fail validate; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation rejects a sidecar reference to a listed skill`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(fixture.repoRoot, "bill-listed", "bill-listed", "Listed skill.")
    seedSkill(
      fixture.repoRoot,
      "bill-referrer",
      "bill-referrer",
      "Listed skill referencing a listed skill as a sidecar.",
      body = "Read the file `bill-listed.md` and execute it.",
    )

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    assertTrue(
      report.issues.any { it.contains("bill-referrer") && it.contains("renders no sidecar") },
      "referencing a listed skill as a sidecar file must fail validate; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation accepts the parent referencing its own child sidecar`() {
    val fixture =
      setupParentWithInternalChild(
        parentBody = "Read the file `bill-feature-helper.md` located in this skill's installed directory.",
      )

    val report =
      RepoValidationRuntime.validateRepo(
        fixture.repoRoot,
        testNativeAgentCompositionContext(fixture.repoRoot),
      )

    val referenceIssues = report.issues.filter { it.contains("references sidecar") }
    assertTrue(
      referenceIssues.isEmpty(),
      "the parent's own-child sidecar reference is the supported dispatch contract, got: $referenceIssues",
    )
  }

  @Test
  fun `selected pack child stages as a sidecar inside the parent staged directory`() {
    val fixture = setupParentWithInternalPackChild()

    val rendered =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )

    val sidecar = rendered.stagingDir.resolve("${fixture.packChildName}.md")
    assertTrue(Files.isRegularFile(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS), "missing pack sidecar at $sidecar")
    assertTrue(sidecar in rendered.renderedSidecarFiles, "pack sidecar not reported in renderedSidecarFiles")
    val packChildTarget = resolveTarget(fixture.repoRoot, fixture.packChildName)
    assertEquals(
      renderWrapper(packChildTarget),
      Files.readString(sidecar.toPath()),
      "pack sidecar must carry the same full governed wrapper a listed pack skill would render",
    )
    listOf("review-orchestrator.md", "specialist-contract.md").forEach { name ->
      val stagedPointer = rendered.stagingDir.resolve(name)
      assertTrue(Files.isRegularFile(stagedPointer.toPath(), LinkOption.NOFOLLOW_LINKS), "missing child pointer $name")
      assertTrue(stagedPointer in rendered.renderedPointerFiles, "$name must be reported as a rendered pointer")
    }
  }

  @Test
  fun `unselected pack child contributes no sidecar and no hash contribution`() {
    val fixture = setupParentWithInternalPackChild()

    val unselected = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertTrue(unselected.renderedSidecarFiles.isEmpty(), "unselected pack must stage no sidecars")
    assertFalse(
      Files.exists(unselected.stagingDir.resolve("${fixture.packChildName}.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "unselected pack sidecar must not be written",
    )

    val selected =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    assertNotEquals(
      unselected.contentHash,
      selected.contentHash,
      "selecting the pack must invalidate the parent's content hash (PD3 selection-aware hashing)",
    )
  }

  @Test
  fun `editing the pack child content invalidates the parent content hash`() {
    val fixture = setupParentWithInternalPackChild()

    val first =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )

    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) + "\n\n## Additional reviewed section.\n",
    )
    val afterEdit =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    assertNotEquals(
      first.contentHash,
      afterEdit.contentHash,
      "editing the pack child's content.md must invalidate the parent hash",
    )
  }

  @Test
  fun `cache reuse re-renders an externally deleted pack sidecar`() {
    val fixture = setupParentWithInternalPackChild()

    val first =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    val sidecar = first.stagingDir.resolve("${fixture.packChildName}.md")
    Files.delete(sidecar.toPath())

    val second =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(second.stagingDir.resolve("${fixture.packChildName}.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "a pruned pack sidecar must be re-rendered instead of reused broken",
    )
  }

  @Test
  fun `cache reuse re-renders an externally deleted child support pointer`() {
    val fixture = setupParentWithInternalPackChild()
    val first =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )
    Files.delete(first.stagingDir.resolve("specialist-contract.md").toPath())

    val second =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          selectedPackSkills = listOf(fixture.packChildPlanSkill),
        ),
      )

    assertEquals(first.contentHash, second.contentHash)
    assertTrue(Files.isRegularFile(second.stagingDir.resolve("specialist-contract.md").toPath()))
  }
}
