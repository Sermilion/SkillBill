package skillbill.engine.featuretask.phase.prompt.compose

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import skillbill.ports.featuretask.PhaseInstructionFileSource
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PhaseProjectInstructionsTest {
  @TempDir
  lateinit var tempDir: Path

  private val repoRoot: Path get() = tempDir.resolve("repo")

  private val projectStoreRoot: Path get() = tempDir.resolve("project-store")

  private val source by lazy { FileSystemPhaseInstructionFileSource(FixedProjectStorePort(projectStoreRoot)) }

  private val projectPhases: Path get() = projectStoreRoot.resolve("phases")

  @Test
  fun `a present phase file yields its content and the sha256 of its bytes`() {
    writeRepoFile("qa.md", "Check the login flow.\n")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    val injected = outcome.injected.single()
    assertEquals(".skill-bill/phases/qa.md", injected.path)
    assertEquals("Check the login flow.\n", injected.content)
    assertEquals(sha256("Check the login flow.\n".toByteArray()), injected.sha256)
    assertEquals(emptyList(), outcome.skipped)
  }

  @Test
  fun `missing phases directories resolve to nothing`() {
    assertEquals(PhaseProjectInstructionsOutcome(), PhaseProjectInstructions.resolve(source, repoRoot, "qa"))
  }

  @Test
  fun `a project store phase file alone is injected under its absolute path`() {
    writeProjectFile("qa.md", "Flags live in the developer settings.\n")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    val injected = outcome.injected.single()
    assertEquals(projectPhases.resolve("qa.md").toString(), injected.path)
    assertEquals("Flags live in the developer settings.\n", injected.content)
    assertEquals(sha256("Flags live in the developer settings.\n".toByteArray()), injected.sha256)
    assertEquals(emptyList(), outcome.skipped)
  }

  @Test
  fun `repository and project store files are both injected with the repository file first`() {
    writeRepoFile("qa.md", "Shared team guidance.")
    writeProjectFile("qa.md", "Local operator guidance.")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(
      listOf(".skill-bill/phases/qa.md", projectPhases.resolve("qa.md").toString()),
      outcome.injected.map { it.path },
    )
    assertEquals(listOf("Shared team guidance.", "Local operator guidance."), outcome.injected.map { it.content })
  }

  @Test
  fun `a bad project store file is skipped while the repository file still resolves`() {
    writeRepoFile("qa.md", "Shared team guidance.")
    writeProjectBytes("qa.md", byteArrayOf(0x41, 0xC3.toByte(), 0x28))
    writeProjectFile("deploy.md", "ignored")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(listOf(".skill-bill/phases/qa.md"), outcome.injected.map { it.path })
    assertEquals(
      listOf(projectPhases.resolve("deploy.md").toString(), projectPhases.resolve("qa.md").toString()),
      outcome.skipped.map { it.path },
    )
    assertContains(outcome.skipped[0].reason, "unknown phase id 'deploy'")
    assertContains(outcome.skipped[1].reason, "unreadable")
  }

  @Test
  fun `an oversized project store file is skipped with the cap in the reason`() {
    writeProjectFile("qa.md", "x".repeat(PhaseProjectInstructions.MAX_BYTES + 1))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(emptyList(), outcome.injected)
    assertContains(outcome.skipped.single().reason, "exceeds 16 KiB cap")
  }

  @Test
  fun `a failing project store lookup is skipped without losing the repository file`() {
    writeRepoFile("qa.md", "Shared team guidance.")
    val failingStore =
      object : PhaseInstructionFileSource by source {
        override fun projectPhasesDirectory(repoRoot: Path): Path = throw SecurityException("denied")
      }

    val outcome = PhaseProjectInstructions.resolve(failingStore, repoRoot, "qa")

    assertEquals(listOf(".skill-bill/phases/qa.md"), outcome.injected.map { it.path })
    assertContains(outcome.skipped.single().reason, "unreadable: denied")
  }

  @Test
  fun `an oversized file is skipped with the cap in the reason`() {
    writeRepoFile("qa.md", "x".repeat(PhaseProjectInstructions.MAX_BYTES + 1))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(emptyList(), outcome.injected)
    assertContains(outcome.skipped.single().reason, "exceeds 16 KiB cap")
  }

  @Test
  fun `a directory named like the phase file is skipped as unreadable without throwing`() {
    Files.createDirectories(repoRoot.resolve(".skill-bill/phases/qa.md"))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(emptyList(), outcome.injected)
    assertContains(outcome.skipped.single().reason, "unreadable")
  }

  @Test
  fun `malformed UTF-8 is skipped as unreadable`() {
    writeRepoBytes("qa.md", byteArrayOf(0x41, 0xC3.toByte(), 0x28))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(emptyList(), outcome.injected)
    assertContains(outcome.skipped.single().reason, "unreadable")
  }

  @Test
  fun `an unknown filename is skipped while the launch's own file still resolves`() {
    writeRepoFile("deploy.md", "ignored")
    writeRepoFile("qa.md", "Check the login flow.")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertEquals(listOf(".skill-bill/phases/qa.md"), outcome.injected.map { it.path })
    assertEquals(".skill-bill/phases/deploy.md", outcome.skipped.single().path)
    assertContains(outcome.skipped.single().reason, "unknown phase id 'deploy'")
  }

  @Test
  fun `code_review md resolves for the review phase in either location`() {
    writeRepoFile("code_review.md", "Focus on API compatibility.")
    writeProjectFile("code_review.md", "Check the local conventions.")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "review")

    assertEquals(
      listOf(".skill-bill/phases/code_review.md", projectPhases.resolve("code_review.md").toString()),
      outcome.injected.map { it.path },
    )
    assertEquals(emptyList(), outcome.skipped)
  }

  private fun writeRepoFile(
    name: String,
    content: String,
  ) = writeRepoBytes(name, content.toByteArray())

  private fun writeRepoBytes(
    name: String,
    bytes: ByteArray,
  ) = writeBytes(repoRoot.resolve(".skill-bill/phases"), name, bytes)

  private fun writeProjectFile(
    name: String,
    content: String,
  ) = writeProjectBytes(name, content.toByteArray())

  private fun writeProjectBytes(
    name: String,
    bytes: ByteArray,
  ) = writeBytes(projectPhases, name, bytes)

  private fun writeBytes(
    directory: Path,
    name: String,
    bytes: ByteArray,
  ) {
    Files.createDirectories(directory)
    Files.write(directory.resolve(name), bytes)
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
