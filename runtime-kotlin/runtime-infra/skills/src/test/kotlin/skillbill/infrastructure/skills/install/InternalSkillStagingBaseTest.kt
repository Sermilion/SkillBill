package skillbill.infrastructure.skills.install

import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.install.apply.NativeAgentSourceRootsRequest
import skillbill.infrastructure.skills.install.apply.nativeAgentSourceRoots
import skillbill.infrastructure.skills.install.apply.standaloneInstallableSkills
import skillbill.infrastructure.skills.install.plan.InstallContext
import skillbill.infrastructure.skills.install.plan.installSkill
import skillbill.infrastructure.skills.install.staging.StageInstalledSkillInput
import skillbill.infrastructure.skills.install.staging.discoverInternalSidecarTargets
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.install.staging.writeInternalSidecarFiles
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation

class InternalSkillStagingBaseTest : InternalSkillStagingTestSupport() {
  @Test
  fun `internal child renders as a sidecar inside the parent staged directory`() {
    val fixture = setupParentWithInternalChild()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val sidecar = rendered.stagingDir.resolve("${fixture.childName}.md")
    assertTrue(Files.isRegularFile(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS), "missing sidecar at $sidecar")
    assertTrue(sidecar in rendered.renderedSidecarFiles, "sidecar not reported in renderedSidecarFiles")
    val childTarget = resolveTarget(fixture.repoRoot, fixture.childName)
    assertEquals(
      renderWrapper(childTarget),
      Files.readString(sidecar.toPath()),
      "sidecar must carry the governed wrapper",
    )
    assertFalse(
      Files.exists(rendered.stagingDir.resolve("content.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "parent staging must not carry a redundant verbatim content.md",
    )
    assertFalse(
      Files.exists(rendered.stagingDir.resolve("${fixture.childName}/content.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "internal child must not stage a nested content.md copy",
    )
    val sidecarText = Files.readString(sidecar.toPath())
    assertTrue(sidecarText.contains("## Execution"), "rendered sidecar must keep ## Execution")
    assertTrue(
      sidecarText.contains("Authored internal body."),
      "rendered sidecar wrapper must stay full and self-contained",
    )
  }

  @Test
  fun `internal child has no standalone staged directory or staging intent`() {
    val fixture = setupParentWithInternalChild()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val cacheRoot = fixture.home.resolve(".skill-bill/installed-skills")
    val childStagingDirs =
      Files.walk(cacheRoot).use { stream ->
        stream
          .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
          .filter { it.fileName.toString().startsWith(fixture.childName) }
          .toList()
      }
    assertTrue(childStagingDirs.isEmpty(), "internal skill must not have its own staging dir; found $childStagingDirs")
    assertFalse(
      rendered.copiedAuthoredFiles.any { it.fileName == "${fixture.childName}.md" },
      "sidecar must not be classified as authored copy",
    )
  }

  @Test
  fun `collision guard fails loudly when parent authors the sidecar name`() {
    val fixture = setupParentWithInternalChild()
    Files.writeString(fixture.parentDir.resolve("${fixture.childName}.md"), "authored collision\n")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INTERNAL_SKILL_SIDECAR_COLLISION, failure.code)
      }
    assertContains(error.message.orEmpty(), "inside parent '${fixture.parentName}'")
    assertContains(error.message.orEmpty(), "Internal skill '${fixture.childName}'")
    assertContains(error.message.orEmpty(), "sidecar '${fixture.childName}.md'")
  }

  @Test
  fun `idempotent reinstall reuses the parent staging dir with the sidecar intact`() {
    val fixture = setupParentWithInternalChild()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val firstSidecar = first.stagingDir.resolve("${fixture.childName}.md")
    assertTrue(Files.isRegularFile(firstSidecar.toPath(), LinkOption.NOFOLLOW_LINKS))

    val second = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertEquals(first.stagingDir, second.stagingDir)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(firstSidecar.toPath(), LinkOption.NOFOLLOW_LINKS),
      "sidecar must survive reuse",
    )
    assertTrue(
      second.renderedSidecarFiles.any { it.fileName == "${fixture.childName}.md" },
      "reused staging must still report the sidecar",
    )
  }

  @Test
  fun `cache reuse re-renders when an expected sidecar was externally deleted`() {
    val fixture = setupParentWithInternalChild()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val sidecar = first.stagingDir.resolve("${fixture.childName}.md")
    Files.delete(sidecar.toPath())

    val second = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(second.stagingDir.resolve("${fixture.childName}.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "a pruned sidecar must be re-rendered instead of reused broken",
    )
  }

  @Test
  fun `discoverInternalSidecarTargets excludes listed siblings and children of other parents`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(fixture.repoRoot, "bill-listed-sibling", "bill-listed-sibling", "Listed sibling.")
    seedSkill(fixture.repoRoot, "bill-other", "bill-other", "Another listed parent.")
    seedInternalChild(fixture.repoRoot, "bill-other-child", "bill-other")

    val targets =
      discoverInternalSidecarTargets(
        repoRoot = fixture.repoRoot,
        parentSkillName = fixture.parentName,
        skillsRoot = fixture.repoRoot.resolve("skills"),
      )

    assertEquals(
      listOf(fixture.childName),
      targets.map { it.skillName },
      "only children declaring '${fixture.parentName}' may stage into its directory",
    )
  }

  @Test
  fun `discoverInternalSidecarTargets returns empty when no child declares the parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-noop").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature", "bill-feature", "Listed skill.")
    Files.createDirectories(repoRoot.resolve("skills"))

    val targets =
      discoverInternalSidecarTargets(
        repoRoot = repoRoot,
        parentSkillName = "bill-feature",
        skillsRoot = repoRoot.resolve("skills"),
      )

    assertTrue(targets.isEmpty())
  }

  @Test
  fun `staged output is byte-identical to a repo without internal-for declarations`() {
    val fixture = setupParentWithInternalChild()

    Files.writeString(
      fixture.childDir.resolve("content.md"),
      """
      ---
      name: ${fixture.childName}
      description: Now listed.
      ---

      Body.
      """.trimIndent(),
    )

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val sidecar = rendered.stagingDir.resolve("${fixture.childName}.md")
    assertFalse(
      Files.exists(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS),
      "parent staging must not carry a sidecar when no child declares internal-for",
    )
    assertTrue(rendered.renderedSidecarFiles.isEmpty(), "no sidecars expected")
  }

  @Test
  fun `parent content hash ignores listed siblings and changes only for internal children`() {
    val (repoRoot, home) = setupRepoBase()
    val parentDir = seedSkill(repoRoot, "bill-feature", "bill-feature", "Routing parent.")

    val alone = stageInstalledSkill(repoRoot, parentDir, home)

    seedSkill(repoRoot, "bill-feature-helper", "bill-feature-helper", "Listed sibling.")
    val withListedSibling = stageInstalledSkill(repoRoot, parentDir, home)
    assertEquals(
      alone.contentHash,
      withListedSibling.contentHash,
      "a listed sibling must not change the parent's content hash (criterion 7 byte-identity)",
    )
    assertTrue(withListedSibling.renderedSidecarFiles.isEmpty())

    seedInternalChild(repoRoot, "bill-feature-helper", "bill-feature")
    val withInternalChild = stageInstalledSkill(repoRoot, parentDir, home)
    assertNotEquals(
      alone.contentHash,
      withInternalChild.contentHash,
      "classifying the sibling internal must invalidate the parent's cache entry",
    )
    assertTrue(
      Files.isRegularFile(
        withInternalChild.stagingDir.resolve("bill-feature-helper.md").toPath(),
        LinkOption.NOFOLLOW_LINKS,
      ),
    )
  }

  @Test
  fun `stageInstalledSkill honors an explicit skills root for internal-child discovery`() {
    val fixture = setupParentWithInternalChild()
    val defaultRoot = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val explicitRoot =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          skillsRoot = fixture.repoRoot.resolve("skills"),
        ),
      )
    assertEquals(defaultRoot.contentHash, explicitRoot.contentHash)

    val emptySkillsRoot = Files.createTempDirectory("skillbill-empty-skills").also(tempDirs::add)
    val withoutChildren =
      stageInstalledSkill(
        StageInstalledSkillInput(
          repoRoot = fixture.repoRoot,
          sourceSkillDir = fixture.parentDir,
          home = fixture.home,
          skillsRoot = emptySkillsRoot,
        ),
      )
    assertNotEquals(
      defaultRoot.contentHash,
      withoutChildren.contentHash,
      "an explicit skills root must drive internal-child discovery instead of repoRoot/skills",
    )
    assertTrue(withoutChildren.renderedSidecarFiles.isEmpty())
  }

  @Test
  fun `writeInternalSidecarFiles writes the pre-rendered governed wrapper`() {
    val fixture = setupParentWithInternalChild()
    val child =
      discoverInternalSidecarTargets(
        repoRoot = fixture.repoRoot,
        parentSkillName = fixture.parentName,
        skillsRoot = fixture.repoRoot.resolve("skills"),
      ).single()
    val tempDir = Files.createTempDirectory("skillbill-sidecar-render").also(tempDirs::add)

    val written =
      writeInternalSidecarFiles(
        tempDir = tempDir,
        parentSourceDir = fixture.parentDir,
        children = listOf(child),
      )

    val sidecar = written.single()
    assertEquals("${fixture.childName}.md", sidecar.fileName.toString())

    assertEquals(
      renderWrapper(resolveTarget(fixture.repoRoot, fixture.childName)),
      Files.readString(sidecar),
    )
  }

  @Test
  fun `standaloneInstallableSkills excludes internal skills that nativeAgentSourceRoots retains`() {
    val parent = planSkill("bill-feature", internalFor = null)
    val child = planSkill("bill-feature-helper", internalFor = "bill-feature")
    val packSkill = planSkill("bill-kotlin-code-review", internalFor = null, platformSlug = "kotlin")
    val unselectedPackSkill = planSkill("bill-ios-code-review", internalFor = null, platformSlug = "ios")
    val skills = listOf(parent, child, packSkill, unselectedPackSkill)

    val standalone = standaloneInstallableSkills(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertEquals(
      listOf("bill-feature", "bill-kotlin-code-review"),
      standalone.map { it.name },
      "internal skills and unselected pack skills must not stage standalone or link into skills_dir",
    )

    val sourceRoots =
      nativeAgentSourceRoots(
        NativeAgentSourceRootsRequest(skills = skills, selectedPlatformSlugs = setOf("kotlin")),
      )
    assertTrue(
      child.sourceDir.toPath() in sourceRoots,
      "an internal skill's dir must remain a native-agent source root (native-agent parity)",
    )
    assertFalse(unselectedPackSkill.sourceDir.toPath() in sourceRoots)
  }

  @Test
  fun `install links the parent but creates no skills_dir entry for the internal child`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)
    val agent = AgentTarget("test-agent", agentRoot.toFileLocation())

    installSkill(
      skillPath = fixture.parentDir,
      agentTargets = listOf(agent),
      context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
    )

    assertTrue(Files.isSymbolicLink(agentRoot.resolve(fixture.parentName)), "parent must be linked")
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.childName), LinkOption.NOFOLLOW_LINKS),
      "internal child must not be linked into any agent skills_dir",
    )
  }
}
