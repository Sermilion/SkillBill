package skillbill.infrastructure.skills.install

import skillbill.error.core.DurableInstallStateFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.install.plan.InstallContext
import skillbill.infrastructure.skills.install.plan.installSkill
import skillbill.infrastructure.skills.install.staging.StagedSymlinkTargetInput
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.authoredFilesFor
import skillbill.infrastructure.skills.install.staging.content.computeInstallContentHash
import skillbill.infrastructure.skills.install.staging.content.isContentManagedSkill
import skillbill.infrastructure.skills.install.staging.generatedSupportPointersFor
import skillbill.infrastructure.skills.install.staging.installedSkillStagingDir
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import skillbill.infrastructure.skills.install.staging.resolveStagedSymlinkTarget
import skillbill.infrastructure.skills.install.staging.stageInstalledSkill
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.pointer.renderPointer
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.install.model.AgentTarget
import skillbill.install.model.RenderedSkill
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.testsupport.SkillClassFixtures
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallStagingTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `authored files are copied verbatim into the staging dir`() {
    val fixture = setupFixture()
    val skillSnapshot = snapshotTree(fixture.skillDir)

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertTrue(rendered.copiedAuthoredFiles.isNotEmpty(), "expected authored files to be copied")
    assertFalse(
      Files.exists(rendered.stagingDir.resolve("content.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "listed-skill staging must not carry a verbatim content.md copy",
    )
    skillSnapshot.forEach { (rel, entry) ->
      if (rel == "SKILL.md" || rel == "content.md") {
        return@forEach
      }
      if (entry !is TreeEntry.RegularFile) {
        return@forEach
      }
      val staged = rendered.stagingDir.resolve(rel)
      assertTrue(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS), "missing staged authored file: $rel")
      assertContentEquals(
        entry.bytes,
        Files.readAllBytes(staged.toPath()),
        "byte mismatch for staged authored file $rel",
      )
    }
  }

  @Test
  fun `listed skill staging omits content_md while SKILL_md keeps full Execution body`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertFalse(
      Files.exists(rendered.stagingDir.resolve("content.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "staging must not contain content.md",
    )
    assertTrue(
      Files.isRegularFile(rendered.stagingDir.resolve("SKILL.md").toPath(), LinkOption.NOFOLLOW_LINKS),
      "staging must contain SKILL.md",
    )
    assertTrue(
      Files.isRegularFile(rendered.stagingDir.resolve(".content-hash").toPath(), LinkOption.NOFOLLOW_LINKS),
      "staging must contain .content-hash",
    )
    val skillMd = Files.readString(rendered.renderedSkillFile.toPath())
    assertTrue(skillMd.contains("## Execution"), "SKILL.md must retain ## Execution")
    assertTrue(
      skillMd.contains("Authored body."),
      "SKILL.md must inline the authored Execution body",
    )
    assertFalse(
      skillMd.contains("Follow the instructions in content.md"),
      "SKILL.md must not reintroduce a thin content.md pointer",
    )
  }

  @Test
  fun `content hash incorporates source content_md and mutation forces restage`() {
    val fixture = setupFixture()
    val first = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    assertFalse(Files.exists(first.stagingDir.resolve("content.md").toPath(), LinkOption.NOFOLLOW_LINKS))

    Files.writeString(
      fixture.skillDir.resolve("content.md"),
      Files.readString(fixture.skillDir.resolve("content.md")) + "\nEdited body.\n",
    )
    val second = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertTrue(
      first.contentHash != second.contentHash,
      "editing source content.md must change the install content hash",
    )
    assertTrue(
      first.stagingDir != second.stagingDir,
      "hash drift must force a new staging directory",
    )
    assertFalse(Files.exists(second.stagingDir.resolve("content.md").toPath(), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.readString(second.renderedSkillFile.toPath()).contains("Edited body."))
  }

  @Test
  fun `staging dir contains a freshly rendered SKILL_md`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val target = resolveTarget(fixture.repoRoot, fixture.skillName)
    val expectedSkillBytes = renderWrapper(target).toByteArray(StandardCharsets.UTF_8)
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(rendered.renderedSkillFile.toPath()))
  }

  @Test
  fun `platform pointer files are materialized as installed sidecar content`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    fixture.pointerSpecs.forEach { (manifest, spec) ->
      val staged = rendered.stagingDir.resolve(spec.name)
      assertTrue(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS), "missing pointer file ${spec.name}")
      renderPointer(repoRoot = fixture.repoRoot, packRoot = manifest.packRoot.toPath(), spec = spec)
      val expected = Files.readString(fixture.repoRoot.resolve(spec.target)).trimEnd() + "\n"
      assertEquals(expected, String(Files.readAllBytes(staged.toPath()), StandardCharsets.UTF_8))
      assertFalse(Files.readString(staged.toPath()).contains("../"), "staged pointer ${spec.name} must not dangle")
    }
  }

  @Test
  fun `staged skill exposes platform packs from resolved skill directory`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val packs = rendered.stagingDir.resolve("platform-packs")
    val manifest = packs.resolve("sample/platform.yaml")
    assertTrue(Files.isSymbolicLink(packs.toPath()), "platform-packs must be present in the staged skill dir")
    assertTrue(
      Files.isRegularFile(manifest.toPath()),
      "manifest must resolve from the staged skill dir at ${rendered.stagingDir.relativize(manifest)}",
    )
    assertEquals(
      "sample",
      Files.readString(manifest.toPath())
        .lineSequence()
        .first { it.startsWith("platform:") }
        .substringAfter('"')
        .substringBefore('"'),
    )
  }

  @Test
  fun `generated supporting pointers for skills are materialized in staging`() {
    val repoRoot = Files.createTempDirectory("skillbill-install-staging-skill-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-staging-skill-home").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/skill-bill")
    Files.createDirectories(skillDir)
    seedTopLevelSkillContent(skillDir)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    val targets = supportingFileTargets(repoRoot)
    requiredSupportingFilesForSkill("skill-bill", repoRoot).map(targets::getValue).forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "supporting target\n")
    }

    val rendered = stageInstalledSkill(repoRoot, skillDir, home)

    requiredSupportingFilesForSkill("skill-bill", repoRoot).forEach { fileName ->
      val sourceSidecar = skillDir.resolve(fileName)
      assertFalse(Files.exists(sourceSidecar, LinkOption.NOFOLLOW_LINKS), "source must not contain $fileName")
      val staged = rendered.stagingDir.resolve(fileName)
      assertTrue(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS), "missing staged $fileName")
      val target = supportingFileTargets(repoRoot).getValue(fileName)
      val expected = Files.readString(target).trimEnd() + "\n"
      assertEquals(
        expected,
        Files.readString(staged.toPath()),
        "support sidecar must inline canonical content for $fileName",
      )
      assertFalse(
        Files.readString(staged.toPath()).contains("../"),
        "inlined sidecar must not carry a dangling relative path",
      )
    }
  }

  @Test
  fun `stale source generated artifacts are excluded from authored copy and replaced in staging`() {
    val fixture = setupFixture()
    val staleSkill = fixture.skillDir.resolve("SKILL.md")
    val stalePointer = fixture.skillDir.resolve(fixture.pointerNames.single())
    Files.writeString(staleSkill, "stale wrapper from source\n")
    Files.writeString(stalePointer, "stale pointer from source\n")
    val sourceBefore = snapshotTree(fixture.repoRoot)

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val reused = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val target = resolveTarget(fixture.repoRoot, fixture.skillName)
    val expectedSkillBytes = renderWrapper(target).toByteArray(StandardCharsets.UTF_8)
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(rendered.renderedSkillFile.toPath()))
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(reused.renderedSkillFile.toPath()))
    fixture.pointerSpecs.forEach { (manifest, spec) ->
      val staged = rendered.stagingDir.resolve(spec.name)
      val reusedStaged = reused.stagingDir.resolve(spec.name)
      renderPointer(repoRoot = fixture.repoRoot, packRoot = manifest.packRoot.toPath(), spec = spec)
      val expected = Files.readString(fixture.repoRoot.resolve(spec.target)).trimEnd() + "\n"
      assertEquals(expected, String(Files.readAllBytes(staged.toPath()), StandardCharsets.UTF_8))
      assertEquals(expected, String(Files.readAllBytes(reusedStaged.toPath()), StandardCharsets.UTF_8))
    }
    assertGeneratedArtifactsExcludedFromAuthoredCopy(rendered, fixture)
    assertGeneratedArtifactsExcludedFromAuthoredCopy(reused, fixture)
    val renderedPointers =
      reused.renderedPointerFiles
        .map { path -> reused.stagingDir.relativize(path).toString().replace(File.separatorChar, '/') }
        .toSet()
    fixture.pointerNames.forEach { pointerName ->
      assertTrue(pointerName in renderedPointers, "cache-hit result must classify $pointerName as rendered pointer")
    }
    assertEquals(sourceBefore, snapshotTree(fixture.repoRoot), "install staging must not mutate source tree")
  }

  @Test
  fun `source tree is byte identical before and after install`() {
    val fixture = setupFixture()
    val before = snapshotTree(fixture.repoRoot)

    stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val after = snapshotTree(fixture.repoRoot)
    assertEquals(before.keys, after.keys, "file set under repoRoot changed after install")
    before.forEach { (rel, entry) ->
      assertEquals(entry, after[rel], "source tree mutated at $rel during install")
    }
  }

  @Test
  fun `installed symlink target equals the staging dir`() {
    val fixture = setupFixture()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)
    val agent = AgentTarget("test-agent", agentRoot.toFileLocation())

    val pointers = applicablePointers(fixture.repoRoot, fixture.skillDir)
    val authored = authoredFilesFor(fixture.skillDir, pointers)
    val expectedHash = computeInstallContentHash(fixture.skillDir, authored, pointers)
    val expectedTarget = installedSkillStagingDir(fixture.home, fixture.skillDir, expectedHash)

    val created =
      installSkill(
        skillPath = fixture.skillDir,
        agentTargets = listOf(agent),
        context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
      ).linkPaths

    assertEquals(1, created.size)
    val link = created.single()
    assertTrue(Files.isSymbolicLink(link))
    val readTarget = Files.readSymbolicLink(link)
    assertEquals(expectedTarget.toRealPath(), readTarget.toRealPath())
  }

  @Test
  fun `install fails closed when content_md frontmatter is malformed`() {
    val fixture = setupFixture()
    Files.writeString(fixture.skillDir.resolve("content.md"), "no frontmatter here\n")
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    val priorChildren = listCacheChildren(cacheRoot)

    assertFailsWith<SkillBillRuntimeException> {
      stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    }
    val afterChildren = listCacheChildren(cacheRoot)
    val newChildren = afterChildren - priorChildren
    assertTrue(
      newChildren.isEmpty(),
      "expected no leftover dirs after failure but found: $newChildren",
    )
  }

  @Test
  fun `install fails closed when a declared pointer target is missing`() {
    val fixture = setupFixture()

    val firstSpec = fixture.pointerSpecs.first().second
    Files.delete(fixture.repoRoot.resolve(firstSpec.target))
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    val priorChildren = listCacheChildren(cacheRoot)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
      }
    assertEquals(DurableInstallStateFailureCode.INSTALL_STAGING, error.code)
    val newChildren = listCacheChildren(cacheRoot) - priorChildren
    assertTrue(
      newChildren.isEmpty(),
      "expected cache root to be untouched on failure but found: $newChildren",
    )
  }

  @Test
  fun `idempotent reinstall reuses the existing staging dir via the content hash marker`() {
    val fixture = setupFixture()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val cacheRoot = installedSkillsCacheRoot(fixture.home)

    val sentinel = first.stagingDir.resolve("sentinel.txt")
    Files.writeString(sentinel.toPath(), "x")

    val second = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertEquals(first.stagingDir, second.stagingDir)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(sentinel.toPath(), LinkOption.NOFOLLOW_LINKS),
      "expected sentinel.txt to survive reuse but staging dir was rebuilt",
    )
    val staleTemps = listCacheChildren(cacheRoot).filter { it.startsWith(".staging-tmp-") }
    assertTrue(staleTemps.isEmpty(), "no leftover .staging-tmp- dirs expected, got: $staleTemps")
  }

  @Test
  fun `prune does not delete unrelated skills whose slug shares the current slug as a prefix`() {
    val fixture = setupFixture()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val cacheRoot = installedSkillsCacheRoot(fixture.home)

    val firstSlug = first.stagingDir.fileName.substringBeforeLast('-')
    val unrelatedLeaf = "$firstSlug-security-0123456789abcdef"
    val unrelatedDir = cacheRoot.resolve(unrelatedLeaf)
    Files.createDirectories(unrelatedDir)
    Files.writeString(unrelatedDir.resolve("marker.txt"), "keep me")

    Files.writeString(fixture.skillDir.resolve("notes.md"), "verbatim notes v2\n")

    val second = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertTrue(
      Files.isDirectory(unrelatedDir, LinkOption.NOFOLLOW_LINKS),
      "prune deleted an unrelated skill's staging dir at $unrelatedDir",
    )
    assertTrue(
      Files.isRegularFile(unrelatedDir.resolve("marker.txt"), LinkOption.NOFOLLOW_LINKS),
      "prune mutated an unrelated skill's staging dir contents",
    )

    assertFalse(
      Files.isDirectory(first.stagingDir.toPath(), LinkOption.NOFOLLOW_LINKS),
      "expected prior same-slug staging dir ${first.stagingDir} to be pruned by ${second.stagingDir}",
    )
  }

  @Test
  fun `content hash changes when support pointer target bytes change and stabilises without mutation`() {
    val repoRoot = Files.createTempDirectory("skillbill-hash-invalidation-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-hash-invalidation-home").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/skill-bill")
    Files.createDirectories(skillDir)
    seedTopLevelSkillContent(skillDir)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    val targets = supportingFileTargets(repoRoot)
    requiredSupportingFilesForSkill("skill-bill", repoRoot).map(targets::getValue).forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "original content\n")
    }
    val pointers = applicablePointers(repoRoot, skillDir)
    val supportPointers = generatedSupportPointersFor(repoRoot, skillDir, "skill-bill")
    val authored = authoredFilesFor(skillDir, pointers, supportPointers)

    val hashBefore = computeInstallContentHash(skillDir, authored, pointers, supportPointers)
    val firstTarget = supportPointers.first().target
    Files.writeString(firstTarget, "mutated content\n")
    val hashAfterMutation = computeInstallContentHash(skillDir, authored, pointers, supportPointers)
    Files.writeString(firstTarget, "original content\n")
    val hashAfterRestore = computeInstallContentHash(skillDir, authored, pointers, supportPointers)

    assertTrue(hashBefore != hashAfterMutation, "hash must change when support pointer target bytes change")
    assertEquals(hashBefore, hashAfterRestore, "hash must stabilise after restoring target bytes")
  }

  @Test
  fun `content hash changes when platform pointer target bytes change`() {
    val fixture = setupFixture()
    val pointers = applicablePointers(fixture.repoRoot, fixture.skillDir)
    val authored = authoredFilesFor(fixture.skillDir, pointers)
    val hashBefore = computeInstallContentHash(fixture.skillDir, authored, pointers)
    val target = fixture.repoRoot.resolve(pointers.single().second.target)

    Files.writeString(target, "# Review orchestrator\n\nChanged guidance.\n")
    val hashAfterMutation = computeInstallContentHash(fixture.skillDir, authored, pointers)

    assertTrue(hashBefore != hashAfterMutation, "hash must change when platform pointer target bytes change")
  }

  @Test
  fun `skill-bill stages dynamic agent addon pointer and addon edits invalidate only its hash`() {
    val repo = Files.createTempDirectory("skillbill-agent-addon-staging").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-agent-addon-home").also(tempDirs::add)
    val feature = repo.resolve("skills/skill-bill")
    val unrelated = repo.resolve("skills/bill-unrelated")
    Files.createDirectories(feature)
    Files.createDirectories(unrelated)
    Files.writeString(
      feature.resolve("content.md"),
      "---\nname: skill-bill\ndescription: Skill Bill router.\n---\n\nBody.\n",
    )
    Files.writeString(unrelated.resolve("content.md"), "---\nname: bill-unrelated\ndescription: Other.\n---\n\nBody.\n")
    Files.createDirectories(feature.resolve("references"))
    Files.writeString(feature.resolve("references/agent-addon-review-helper.md"), "Authored nested reference.\n")
    SkillClassFixtures.seedShippedSkillClasses(repo)
    val targets = supportingFileTargets(repo)
    requiredSupportingFilesForSkill("skill-bill", repo).map(targets::getValue).forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "support\n")
    }
    val addon = repo.resolve("agent-addons/review-helper")
    Files.createDirectories(addon)
    Files.writeString(
      addon.resolve("agent-addon.yaml"),
      "contract_version: \"1.0\"\nslug: review-helper\ndescription: Review helper\n" +
        "agent_ids:\n  - codex\nconsumers:\n  - skill-bill\n",
    )
    Files.writeString(addon.resolve("content.md"), "Addon body.\n")

    val firstFeature = stageInstalledSkill(repo, feature, home)
    val firstUnrelated = stageInstalledSkill(repo, unrelated, home)
    assertEquals("Addon body.\n", stagedText(firstFeature, "agent-addon-review-helper.md"))
    assertEquals("Authored nested reference.\n", stagedText(firstFeature, "references/agent-addon-review-helper.md"))

    Files.writeString(addon.resolve("content.md"), "Changed addon body.\n")
    val secondFeature = stageInstalledSkill(repo, feature, home)
    val secondUnrelated = stageInstalledSkill(repo, unrelated, home)

    assertTrue(firstFeature.contentHash != secondFeature.contentHash)
    assertEquals(firstUnrelated.contentHash, secondUnrelated.contentHash)
    assertEquals("Changed addon body.\n", stagedText(secondFeature, "agent-addon-review-helper.md"))

    Files.writeString(
      addon.resolve("agent-addon.yaml"),
      Files.readString(addon.resolve("agent-addon.yaml")).replace("Review helper", "Updated review helper"),
    )
    val thirdFeature = stageInstalledSkill(repo, feature, home)
    val thirdUnrelated = stageInstalledSkill(repo, unrelated, home)

    assertTrue(secondFeature.contentHash != thirdFeature.contentHash)
    assertEquals(secondUnrelated.contentHash, thirdUnrelated.contentHash)
    assertEquals("Changed addon body.\n", stagedText(thirdFeature, "agent-addon-review-helper.md"))
  }

  @Test
  fun `cache root is under tempHome dot skill-bill installed-skills outside the repo`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val expectedRoot = fixture.home.resolve(".skill-bill/installed-skills").toAbsolutePath().normalize()
    assertTrue(
      rendered.stagingDir.toPath().toAbsolutePath().normalize().startsWith(expectedRoot),
      "staging dir ${rendered.stagingDir} not under expected cache root $expectedRoot",
    )
    assertEquals(expectedRoot, installedSkillsCacheRoot(fixture.home))
    assertTrue(
      rendered.renderedSkillFile.toPath().toAbsolutePath().normalize().startsWith(rendered.stagingDir.toPath()),
      "rendered SKILL.md ${rendered.renderedSkillFile} was not written under ${rendered.stagingDir}",
    )
    assertTrue(
      rendered.renderedPointerFiles.all { pointer ->
        pointer.toPath().toAbsolutePath().normalize().startsWith(rendered.stagingDir.toPath())
      },
      "rendered pointer files must stay inside the install staging dir",
    )
    assertFalse(
      rendered.stagingDir.toPath().toAbsolutePath().normalize().startsWith(
        fixture.repoRoot.toAbsolutePath().normalize(),
      ),
      "staging dir ${rendered.stagingDir} unexpectedly inside repo ${fixture.repoRoot}",
    )
  }

  @Test
  fun `content-managed staging target resolves under the home cache outside the source repoRoot`() {
    val fixture = setupFixture()

    val target =
      resolveStagedSymlinkTarget(
        StagedSymlinkTargetInput(
          resolvedSkill = fixture.skillDir,
          repoRoot = fixture.repoRoot,
          home = fixture.home,
          manifests = fixture.pointerSpecs.map { it.first }.distinct(),
        ),
      ).toAbsolutePath().normalize()

    assertTrue(
      target.startsWith(fixture.home.resolve(".skill-bill/installed-skills").toAbsolutePath().normalize()),
      "content-managed staging target $target must resolve under the home cache",
    )
    assertFalse(
      target.startsWith(fixture.repoRoot.toAbsolutePath().normalize()),
      "content-managed staging target $target unexpectedly resolves inside the source repoRoot ${fixture.repoRoot}",
    )
  }

  @Test
  fun `non-content-managed fallback targets the copy skill dir and never a sibling clone`() {
    val home = Files.createTempDirectory("skillbill-fallback-copy-home").also(tempDirs::add)
    val copyRoot = home.resolve(".skill-bill/source").also { Files.createDirectories(it) }
    val cloneRoot = Files.createTempDirectory("skillbill-fallback-clone-repo").also(tempDirs::add)

    val copySkillDir = copyRoot.resolve("skills/legacy-link-skill")
    val cloneSkillDir = cloneRoot.resolve("skills/legacy-link-skill")
    listOf(copySkillDir, cloneSkillDir).forEach { dir ->
      Files.createDirectories(dir)
      Files.writeString(dir.resolve("SKILL.md"), "# legacy ad-hoc skill\n")
    }
    assertFalse(isContentManagedSkill(copySkillDir), "fixture must be non-content-managed (no content.md)")

    val target =
      resolveStagedSymlinkTarget(
        StagedSymlinkTargetInput(
          resolvedSkill = copySkillDir,
          repoRoot = copyRoot,
          home = home,
        ),
      ).toAbsolutePath().normalize()

    assertEquals(
      copySkillDir.toAbsolutePath().normalize(),
      target,
      "non-content-managed fallback must target the copy skill dir",
    )
    assertTrue(
      target.startsWith(home.resolve(".skill-bill").toAbsolutePath().normalize()),
      "non-content-managed fallback must resolve under the copy at ~/.skill-bill, was $target",
    )
    assertFalse(
      target.startsWith(cloneRoot.toAbsolutePath().normalize()),
      "non-content-managed fallback must never resolve into the clone, was $target",
    )
  }

  private fun setupFixture(): Fixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-staging-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-staging-home").also(tempDirs::add)
    val skillRelativeDir = "code-review/bill-sample-code-review"
    val packRoot = repoRoot.resolve("platform-packs/sample")
    val skillDir = packRoot.resolve(skillRelativeDir)
    seedSamplePack(repoRoot, packRoot, skillDir, skillRelativeDir)
    seedSampleSkillContent(skillDir)
    val pointers = applicablePointers(repoRoot, skillDir)
    return Fixture(
      repoRoot = repoRoot,
      home = home,
      skillDir = skillDir,
      skillName = skillDir.fileName.toString(),
      pointerSpecs = pointers,
      pointerNames = pointers.map { it.second.name }.toSet(),
    )
  }

  private fun seedSamplePack(
    repoRoot: Path,
    packRoot: Path,
    skillDir: Path,
    skillRelativeDir: String,
  ) {
    Files.createDirectories(skillDir)
    Files.createDirectories(repoRoot.resolve("orchestration/review-orchestrator"))
    Files.writeString(
      repoRoot.resolve("orchestration/review-orchestrator/PLAYBOOK.md"),
      "# Review orchestrator\n",
    )
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "sample"
      |contract_version: "1.8"
      |display_name: "Sample"
      |
      |routing_signals:
      |  strong:
      |    - ".sample"
      |  tie_breakers: []
      |
      |declared_code_review_areas: []
      |
      |declared_files:
      |  baseline: "$skillRelativeDir/content.md"
      |  areas: {}
      |area_metadata: {}
      |
      |pointers:
      |  $skillRelativeDir:
      |    - name: review-orchestrator.md
      |      target: orchestration/review-orchestrator/PLAYBOOK.md
      """.trimMargin(),
    )
  }

  private fun seedSampleSkillContent(skillDir: Path) {
    val frontmatter =
      """
      |---
      |name: bill-sample-code-review
      |description: Sample skill for install staging tests.
      |---
      """.trimMargin() + "\n\nAuthored body.\n"
    Files.writeString(skillDir.resolve("content.md"), frontmatter)

    Files.writeString(skillDir.resolve("notes.md"), "verbatim notes\n")
  }

  private fun seedTopLevelSkillContent(skillDir: Path) {
    val frontmatter =
      """
      |---
      |name: skill-bill
      |description: Run governed work.
      |---
      """.trimMargin() + "\n\nAuthored review guidance.\n"
    Files.writeString(skillDir.resolve("content.md"), frontmatter)
  }

  private fun assertGeneratedArtifactsExcludedFromAuthoredCopy(
    rendered: RenderedSkill,
    fixture: Fixture,
  ) {
    val copiedRelative =
      rendered.copiedAuthoredFiles
        .map { path -> rendered.stagingDir.relativize(path).toString().replace(File.separatorChar, '/') }
        .toSet()
    assertFalse("SKILL.md" in copiedRelative, "stale source SKILL.md must not be part of authored copy set")
    fixture.pointerNames.forEach { pointerName ->
      assertFalse(pointerName in copiedRelative, "stale source pointer $pointerName must not be authored copy")
    }
  }

  private fun snapshotTree(root: Path): Map<String, TreeEntry> =
    Files.walk(root).use { stream ->
      stream
        .sorted()
        .filter { it != root }
        .toList()
        .associate { path ->
          val key = root.relativize(path).toString().replace(File.separatorChar, '/')
          val entry: TreeEntry =
            when {
              Files.isSymbolicLink(path) -> TreeEntry.Symlink(Files.readSymbolicLink(path).toString())
              Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> TreeEntry.Directory
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> TreeEntry.RegularFile(Files.readAllBytes(path))
              else -> TreeEntry.Other
            }
          key to entry
        }
    }

  private sealed class TreeEntry {
    data object Directory : TreeEntry()

    data object Other : TreeEntry()

    data class Symlink(val target: String) : TreeEntry()

    class RegularFile(val bytes: ByteArray) : TreeEntry() {
      override fun equals(other: Any?): Boolean = other is RegularFile && bytes.contentEquals(other.bytes)

      override fun hashCode(): Int = bytes.contentHashCode()
    }
  }

  private fun listCacheChildren(cacheRoot: Path): Set<String> {
    if (!Files.isDirectory(cacheRoot)) {
      return emptySet()
    }
    return Files.list(cacheRoot).use { stream ->
      stream.map { it.fileName.toString() }.toList().toSet()
    }
  }

  private fun stagedText(
    rendered: RenderedSkill,
    relativePath: String,
  ): String = Files.readString(rendered.stagingDir.resolve(relativePath).toPath())

  private data class Fixture(
    val repoRoot: Path,
    val home: Path,
    val skillDir: Path,
    val skillName: String,
    val pointerSpecs: List<Pair<PlatformManifest, PointerSpec>>,
    val pointerNames: Set<String>,
  )
}
