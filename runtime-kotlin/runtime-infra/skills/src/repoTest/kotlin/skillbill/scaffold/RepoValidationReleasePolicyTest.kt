package skillbill.scaffold

import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.runtime.validation.ReleaseRefValidationResult
import skillbill.infrastructure.skills.scaffold.runtime.validation.RepoValidationRuntime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepoValidationReleasePolicyTest {
  @Test
  fun `repository validation surfaces malformed agent addon without changing report counts`() {
    val repoRoot = Files.createTempDirectory("skillbill-agent-addon-validation")
    val addon = repoRoot.resolve("agent-addons/review-helper")
    Files.createDirectories(addon)
    Files.writeString(addon.resolve("agent-addon.yaml"), "contract_version: [")
    Files.writeString(addon.resolve("content.md"), "# Fixture\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot, testNativeAgentCompositionContext(repoRoot))

    assertTrue(report.issues.any { it.startsWith("agent-addons:") })
    assertEquals(0, report.skillCount)
    assertEquals(0, report.addonCount)
    assertEquals(0, report.platformPackCount)
  }

  @Test
  fun `release refs preserve semver metadata`() {
    val stable = RepoValidationRuntime.parseReleaseRef("refs/tags/v1.2.3")
    assertEquals("v1.2.3", stable.tag)
    assertEquals("1.2.3", stable.version)
    assertFalse(stable.prerelease)

    val prerelease = RepoValidationRuntime.parseReleaseRef("v2.0.0-rc.1+build.5")
    assertEquals("v2.0.0-rc.1+build.5", prerelease.tag)
    assertEquals("2.0.0-rc.1+build.5", prerelease.version)
    assertTrue(prerelease.prerelease)
  }

  @Test
  fun `release refs reject bare version tags without v prefix`() {
    listOf("0.2.0", "refs/tags/1.0.0-rc.1").forEach { ref ->
      val failure =
        assertFailsWith<IllegalArgumentException> {
          RepoValidationRuntime.parseReleaseRef(ref)
        }
      assertTrue(failure.message.orEmpty().contains("canonical vMAJOR"), ref)
    }
  }

  @Test
  fun `release refs reject non semver tags`() {
    val error =
      kotlin.runCatching {
        RepoValidationRuntime.parseReleaseRef("release-1.0")
      }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error.message.orEmpty().contains("Release tag must match"))
  }

  @Test
  fun `MIT releases pass across version lines without an approval record`() {
    val repoRoot = Files.createTempDirectory("skillbill-mit-release-policy")
    Files.writeString(repoRoot.resolve("LICENSE"), completeMitLicense())

    listOf("v0.1.1", "v0.1.2", "v0.9.9-rc.1", "v1.0.0-rc.1", "v1.0.0", "v1.1.0", "v2.0.0+build.7")
      .forEach { ref ->
        assertIs<ReleaseRefValidationResult.Valid>(RepoValidationRuntime.validateReleaseRef(repoRoot, ref))
      }

    Files.writeString(repoRoot.resolve("LICENSE"), completeMitLicense().replace("\n", "\r\n"))
    assertIs<ReleaseRefValidationResult.Valid>(RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0"))
  }

  @Test
  fun `release policy rejects missing truncated and restricted MIT licenses`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-mit-license")
    val missing =
      assertIs<ReleaseRefValidationResult.Rejected>(RepoValidationRuntime.validateReleaseRef(repoRoot, "v0.1.2"))
    assertTrue(missing.message.contains("LICENSE"))

    listOf(
      "",
      "MIT License",
      completeMitLicense().substringBefore("THE SOFTWARE IS PROVIDED"),
      completeMitLicense().replace("without restriction", "for noncommercial use only"),
    ).forEach { license ->
      Files.writeString(repoRoot.resolve("LICENSE"), license)
      listOf("v0.1.1", "v0.1.2", "v1.0.0-rc.1", "v1.0.0", "v2.0.0").forEach { ref ->
        val failure =
          assertIs<ReleaseRefValidationResult.Rejected>(RepoValidationRuntime.validateReleaseRef(repoRoot, ref))
        assertTrue(failure.message.contains("complete current MIT license"), ref)
      }
    }
  }

  @Test
  fun `manual staging requires a prerelease tag under MIT`() {
    val repoRoot = Files.createTempDirectory("skillbill-mit-staging")
    Files.writeString(repoRoot.resolve("LICENSE"), completeMitLicense())

    val staging =
      assertIs<ReleaseRefValidationResult.Valid>(
        RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0-staging.1", forcePrerelease = true),
      )
    assertTrue(staging.metadata.prerelease)
    val failure =
      assertIs<ReleaseRefValidationResult.Rejected>(
        RepoValidationRuntime.validateReleaseRef(repoRoot, "v1.0.0", forcePrerelease = true),
      )
    assertTrue(failure.message.contains("prerelease identifier"))
  }
}
