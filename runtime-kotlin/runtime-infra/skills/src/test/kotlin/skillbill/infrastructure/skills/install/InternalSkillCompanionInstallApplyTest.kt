package skillbill.infrastructure.skills.install

import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InternalSkillCompanionInstallApplyTest : InstallApplyTestSupport() {
  @Test
  fun `reapply restores an externally deleted internal skill authored companion`() {
    val fixture = setupApplyFixture()
    val internalSkillDir = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    Files.writeString(
      internalSkillDir.resolve("content.md"),
      Files.readString(internalSkillDir.resolve("content.md")) +
        "\nWhen the baseline is insufficient, read [review-guidelines.md](review-guidelines.md).\n",
    )
    Files.writeString(internalSkillDir.resolve("review-guidelines.md"), "governed review rubric\n")
    val plan =
      planInstallForTest(
        fixture.request(
          selectedPlatforms = setOf("kotlin"),
          agents = setOf(SupportedAgent.CODEX),
        ),
      )
    val first = applyInstallForTest(plan)
    val parentStaging =
      first.skills.single { skill -> skill.skillName == PACK_SIDECAR_PARENT_SKILL }.staging.stagingDir
    val companion = assertNotNull(parentStaging).resolve("review-guidelines.md")
    assertTrue(Files.isRegularFile(companion.toPath(), LinkOption.NOFOLLOW_LINKS))
    Files.delete(companion.toPath())

    val second = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(Files.isRegularFile(companion.toPath(), LinkOption.NOFOLLOW_LINKS))
    assertEquals("governed review rubric\n", Files.readString(companion.toPath()))
  }

  @Test
  fun `code quality baseline idioms sidecar stages without a content link`() {
    val fixture = setupApplyFixture()
    val baseline = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    val idioms = "Use nullableString.orEmpty() for an empty fallback.\n"
    Files.writeString(baseline.resolve("code-quality-idioms.md"), idioms)
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val parentStaging =
      result.skills.single { skill -> skill.skillName == PACK_SIDECAR_PARENT_SKILL }.staging.stagingDir
    val companion = assertNotNull(parentStaging).resolve("code-quality-idioms.md").toPath()
    assertTrue(Files.isRegularFile(companion, LinkOption.NOFOLLOW_LINKS))
    assertEquals(idioms, Files.readString(companion))
  }

  @Test
  fun `unlinked non idiom companion remains rejected`() {
    val fixture = setupApplyFixture()
    val baseline = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture")
    Files.writeString(baseline.resolve("unlinked-notes.md"), "unowned notes\n")
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )

    val result = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
  }

  @Test
  fun `reapply rejects a new parent collision before reusing companion staging`() {
    val fixture = setupApplyFixture()
    val internalSkillDir = fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review")
    Files.writeString(
      internalSkillDir.resolve("content.md"),
      Files.readString(internalSkillDir.resolve("content.md")) +
        "\nWhen the baseline is insufficient, read [review-guidelines.md](review-guidelines.md).\n",
    )
    Files.writeString(internalSkillDir.resolve("review-guidelines.md"), "governed review rubric\n")
    val plan =
      planInstallForTest(
        fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)),
      )
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    Files.writeString(
      fixture.repoRoot.resolve("skills/$PACK_SIDECAR_PARENT_SKILL/review-guidelines.md"),
      "parent content\n",
    )

    val second = applyInstallForTest(plan)

    assertEquals(InstallApplyStatus.FAILURE, second.status)
    assertEquals(
      "SkillStagingFailureCode.INTERNAL_SKILL_SIDECAR_COLLISION",
      second.failures.single { issue -> issue.skillName == PACK_SIDECAR_PARENT_SKILL }.causeClass,
    )
  }
}
