package skillbill.infrastructure.skills.install

import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.ports.repository.toFileLocation
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest

open class InternalSkillStagingTestSupport {
  protected val tempDirs = mutableListOf<Path>()

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

  protected data class ParentChildFixture(
    val repoRoot: Path,
    val home: Path,
    val parentName: String,
    val childName: String,
    val parentDir: Path,
    val childDir: Path,
  )

  protected data class ParentWithInternalPackChildFixture(
    val repoRoot: Path,
    val home: Path,
    val parentName: String,
    val parentDir: Path,
    val packChildName: String,
    val packChildDir: Path,
    val packChildContentFile: Path,
    val packChildPlanSkill: InstallPlanSkill,
  )

  protected fun setupRepoBase(): Pair<Path, Path> {
    val repoRoot = Files.createTempDirectory("skillbill-internal-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-internal-home").also(tempDirs::add)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    seedSkill(
      repoRoot,
      PACK_SIDECAR_PARENT_SKILL,
      PACK_SIDECAR_PARENT_SKILL,
      "Routes quality checks and dispatches to pack sidecars.",
    )

    seedKmpPlatformPack(repoRoot)
    seedSupportingTargets(repoRoot)
    return repoRoot to home
  }

  protected fun setupParentWithInternalChild(parentBody: String = "Authored body."): ParentChildFixture {
    val (repoRoot, home) = setupRepoBase()
    val parentName = "bill-feature"
    val childName = "bill-feature-helper"
    val parentDir =
      seedSkill(
        repoRoot,
        parentName,
        parentName,
        "Routes feature work and dispatches to internal sidecars.",
        body = parentBody,
      )
    val childDir = seedInternalChild(repoRoot, childName, parentName)
    return ParentChildFixture(
      repoRoot = repoRoot,
      home = home,
      parentName = parentName,
      childName = childName,
      parentDir = parentDir,
      childDir = childDir,
    )
  }

  protected fun planSkill(
    name: String,
    internalFor: String?,
    platformSlug: String? = null,
  ): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = Path.of("/repo/skills/$name").toAbsolutePath().normalize().toFileLocation(),
      kind = if (platformSlug == null) InstallPlanSkillKind.BASE else InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = platformSlug,
      internalFor = internalFor,
    )

  protected fun seedSupportingTargets(repoRoot: Path) {
    val targets = supportingFileTargets(repoRoot)
    val orchestrationTargets = targets.values.filter { it.startsWith(repoRoot.resolve("orchestration")) }
    orchestrationTargets.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "supporting target\n")
    }
  }

  protected fun seedSkill(
    repoRoot: Path,
    skillName: String,
    frontmatterName: String,
    description: String,
    body: String = "Authored body.",
  ): Path {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $frontmatterName
      description: $description
      ---

      $body
      """.trimIndent(),
    )
    return skillDir.toAbsolutePath().normalize()
  }

  protected fun seedInternalChild(
    repoRoot: Path,
    skillName: String,
    parentName: String,
  ): Path {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Internal dispatch target.
      internal-for: $parentName
      ---

      Authored internal body.
      """.trimIndent(),
    )
    return skillDir.toAbsolutePath().normalize()
  }

  protected fun setupParentWithInternalPackChild(): ParentWithInternalPackChildFixture {
    val (repoRoot, home) = setupRepoBase()
    val parentName = PACK_SIDECAR_PARENT_SKILL
    val parentDir =
      seedSkill(
        repoRoot,
        parentName,
        parentName,
        "Routes code review and dispatches to pack sidecars.",
      )

    val slug = "kotlin"
    val packChildName = "bill-$slug-code-review"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    val packChildDir = packRoot.resolve("code-review").resolve(packChildName)
    Files.createDirectories(packChildDir)
    val packChildContentFile = packChildDir.resolve("content.md")
    Files.writeString(
      packChildContentFile,
      """
      |---
      |name: $packChildName
      |description: Internal pack dispatch target.
      |internal-for: $parentName
      |---
      |
      |Authored internal pack body.
      """.trimMargin(),
    )
    seedKotlinPlatformPackWithBaseline(repoRoot, slug, packChildName)

    val packChildPlanSkill =
      InstallPlanSkill(
        name = packChildName,
        sourceDir = packChildDir.toAbsolutePath().normalize().toFileLocation(),
        kind = InstallPlanSkillKind.PLATFORM_PACK,
        platformSlug = slug,
        internalFor = parentName,
      )
    return ParentWithInternalPackChildFixture(
      repoRoot = repoRoot,
      home = home,
      parentName = parentName,
      parentDir = parentDir.toAbsolutePath().normalize(),
      packChildName = packChildName,
      packChildDir = packChildDir.toAbsolutePath().normalize(),
      packChildContentFile = packChildContentFile.toAbsolutePath().normalize(),
      packChildPlanSkill = packChildPlanSkill,
    )
  }

  protected fun seedKotlinPlatformPackWithBaseline(
    repoRoot: Path,
    slug: String,
    codeReviewName: String,
  ) {
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot)
    val qualityCheckName = "bill-$slug-code-check"
    Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "$slug"
      |contract_version: "1.8"
      |routing_signals:
      |  strong:
      |    - "$slug"
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: "code-review/$codeReviewName/content.md"
      |  areas: {}
      |area_metadata: {}
      |display_name: "$slug"
      |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
      |pointers:
      |  code-review/$codeReviewName:
      |    - name: review-orchestrator.md
      |      target: orchestration/review-orchestrator/PLAYBOOK.md
      |    - name: specialist-contract.md
      |      target: orchestration/review-orchestrator/specialist-contract.md
      |
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
      """
      |---
      |name: $qualityCheckName
      |description: Test quality-check skill.
      |internal-for: skill-bill
      |---
      |Body.
      """.trimMargin(),
    )
  }

  protected fun seedKmpPlatformPack(repoRoot: Path) {
    val slug = "kmp"
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-check"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot.resolve("code-review").resolve(codeReviewName))
    Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "$slug"
      |contract_version: "1.8"
      |routing_signals:
      |  strong:
      |    - "$slug"
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: "code-review/$codeReviewName/content.md"
      |  areas: {}
      |area_metadata: {}
      |display_name: "$slug"
      |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
      |
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("code-review").resolve(codeReviewName).resolve("content.md"),
      """
      |---
      |name: $codeReviewName
      |description: Test code-review skill.
      |---
      |Body.
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
      """
      |---
      |name: $qualityCheckName
      |description: Test quality-check skill.
      |internal-for: skill-bill
      |---
      |Body.
      """.trimMargin(),
    )
  }
}
