package skillbill.infrastructure.skills.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.SkillContentIdentity
import skillbill.infrastructure.skills.install.identity.routeInstalledSkillBody
import skillbill.review.parsing.requireMatch

class SkillContentIdentityTest {
  @Test
  fun `equivalent source staged and symlink identities are accepted without reading installed body`() {
    val root = Files.createTempDirectory("skill-content-identity-match")
    val source = root.resolve("bill-example").also(Files::createDirectories)
    Files.writeString(
      source.resolve("content.md"),
      "---\nname: bill-example\ndescription: Example\ninternal-for: bill-feature\n---\n\nBody.\n",
    )
    val sourceIdentity = SkillContentIdentity.fromSource(source)
    val staged = root.resolve("staged").also(Files::createDirectories)
    Files.writeString(staged.resolve(SKILL_CONTENT_IDENTITY_FILENAME), sourceIdentity.compact())
    val symlink = root.resolve("linked-bill-example")
    Files.createSymbolicLink(symlink, source)

    assertEquals(sourceIdentity, SkillContentIdentity.fromInstalled(staged))
    assertEquals(sourceIdentity, SkillContentIdentity.fromSource(symlink))
    assertContains(sourceIdentity.compact(), "exact_content_sha256")
    assertContains(sourceIdentity.compact(), "normalized_metadata")

    assertFalse(Files.exists(staged.resolve("SKILL.md")))
  }

  @Test
  fun `identity mismatch fails loudly with compact supplied and installed identities`() {
    val root = Files.createTempDirectory("skill-content-identity-mismatch")
    val suppliedDir = root.resolve("supplied").also(Files::createDirectories)
    val installedDir = root.resolve("installed").also(Files::createDirectories)
    Files.writeString(suppliedDir.resolve("content.md"), "---\nname: one\ndescription: One\n---\n\nOne.\n")
    Files.writeString(installedDir.resolve("content.md"), "---\nname: two\ndescription: Two\n---\n\nTwo.\n")

    val supplied = SkillContentIdentity.fromSource(suppliedDir)
    val installed = SkillContentIdentity.fromSource(installedDir)
    val installedMarker = root.resolve("installed-marker").also(Files::createDirectories)
    Files.writeString(installedMarker.resolve(SKILL_CONTENT_IDENTITY_FILENAME), installed.compact())
    Files.delete(installedDir.resolve("content.md"))
    val installedFromMarker = SkillContentIdentity.fromInstalled(installedMarker)
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        SkillContentIdentity.requireMatch(supplied, installedFromMarker)
      }.also { assertEquals(ReviewContextFailureCode.SKILL_CONTENT_IDENTITY_MISMATCH, it.code) }

    assertContains(error.message.orEmpty(), supplied.canonicalSourceIdentity)
    assertContains(error.message.orEmpty(), installed.canonicalSourceIdentity)
    assertContains(error.message.orEmpty(), supplied.exactContentSha256)
    assertContains(error.message.orEmpty(), installed.exactContentSha256)
    assertFalse(error.message.orEmpty().contains("One.\n"))
    assertFalse(error.message.orEmpty().contains("Two.\n"))
  }

  @Test
  fun `routing boundary accepts matching marker without loading installed body`() {
    val root = Files.createTempDirectory("skill-content-identity-routing")
    val source = root.resolve("bill-example").also(Files::createDirectories)
    Files.writeString(source.resolve("content.md"), "---\nname: bill-example\ndescription: Example\n---\n\nBody.\n")
    val staging = root.resolve("staged").also(Files::createDirectories)
    val supplied = SkillContentIdentity.fromSource(source)
    Files.writeString(staging.resolve(SKILL_CONTENT_IDENTITY_FILENAME), supplied.compact())
    assertFalse(Files.exists(staging.resolve("SKILL.md")))

    routeInstalledSkillBody(
      suppliedCompactIdentity = supplied.compact(),
      installedStagingDir = staging,
    )

    assertFalse(Files.exists(staging.resolve("SKILL.md")))
  }

  @Test
  fun `routing boundary rejects mismatched marker before loading installed body`() {
    val root = Files.createTempDirectory("skill-content-identity-routing-mismatch")
    val suppliedDir = root.resolve("supplied").also(Files::createDirectories)
    val installedDir = root.resolve("installed").also(Files::createDirectories)
    Files.writeString(
      suppliedDir.resolve("content.md"),
      "---\nname: supplied\ndescription: Supplied\n---\n\nSupplied body.\n",
    )
    Files.writeString(
      installedDir.resolve("content.md"),
      "---\nname: installed\ndescription: Installed\n---\n\nInstalled body.\n",
    )
    val supplied = SkillContentIdentity.fromSource(suppliedDir)
    val installed = SkillContentIdentity.fromSource(installedDir)
    val staging = root.resolve("staged").also(Files::createDirectories)
    Files.writeString(staging.resolve(SKILL_CONTENT_IDENTITY_FILENAME), installed.compact())
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        routeInstalledSkillBody(
          suppliedCompactIdentity = supplied.compact(),
          installedStagingDir = staging,
        )
      }.also { assertEquals(ReviewContextFailureCode.SKILL_CONTENT_IDENTITY_MISMATCH, it.code) }

    assertContains(error.message.orEmpty(), supplied.canonicalSourceIdentity)
    assertContains(error.message.orEmpty(), installed.canonicalSourceIdentity)
  }
}
