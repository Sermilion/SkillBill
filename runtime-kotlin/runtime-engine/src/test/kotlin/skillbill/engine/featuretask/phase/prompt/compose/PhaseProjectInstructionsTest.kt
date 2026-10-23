package skillbill.engine.featuretask.phase.prompt.compose

import org.junit.jupiter.api.io.TempDir
import skillbill.infrastructure.workflow.featuretask.FileSystemPhaseInstructionFileSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PhaseProjectInstructionsTest {
  @TempDir
  lateinit var repoRoot: Path

  private val source = FileSystemPhaseInstructionFileSource()

  @Test
  fun `a present phase file yields its content and the sha256 of its bytes`() {
    writePhaseFile("qa.md", "Check the login flow.\n")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    val injected = assertIs<PhaseProjectInstructionsResolution.Injected>(outcome.injected)
    assertEquals(".skill-bill/phases/qa.md", injected.relativePath)
    assertEquals("Check the login flow.\n", injected.content)
    assertEquals(sha256("Check the login flow.\n".toByteArray()), injected.sha256)
    assertEquals(emptyList(), outcome.skipped)
  }

  @Test
  fun `a missing phases directory resolves to nothing`() {
    assertEquals(PhaseProjectInstructionsOutcome(), PhaseProjectInstructions.resolve(source, repoRoot, "qa"))
  }

  @Test
  fun `an oversized file is skipped with the cap in the reason`() {
    writePhaseFile("qa.md", "x".repeat(PhaseProjectInstructions.MAX_BYTES + 1))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertNull(outcome.injected)
    assertContains(outcome.skipped.single().reason, "exceeds 16 KiB cap")
  }

  @Test
  fun `a directory named like the phase file is skipped as unreadable without throwing`() {
    Files.createDirectories(repoRoot.resolve(".skill-bill/phases/qa.md"))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertNull(outcome.injected)
    assertContains(outcome.skipped.single().reason, "unreadable")
  }

  @Test
  fun `malformed UTF-8 is skipped as unreadable`() {
    writePhaseBytes("qa.md", byteArrayOf(0x41, 0xC3.toByte(), 0x28))

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertNull(outcome.injected)
    assertContains(outcome.skipped.single().reason, "unreadable")
  }

  @Test
  fun `an unknown filename is skipped while the launch's own file still resolves`() {
    writePhaseFile("deploy.md", "ignored")
    writePhaseFile("qa.md", "Check the login flow.")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "qa")

    assertIs<PhaseProjectInstructionsResolution.Injected>(outcome.injected)
    assertEquals(".skill-bill/phases/deploy.md", outcome.skipped.single().relativePath)
    assertContains(outcome.skipped.single().reason, "unknown phase id 'deploy'")
  }

  @Test
  fun `code_review md resolves for the review phase`() {
    writePhaseFile("code_review.md", "Focus on API compatibility.")

    val outcome = PhaseProjectInstructions.resolve(source, repoRoot, "review")

    assertEquals(".skill-bill/phases/code_review.md", outcome.injected?.relativePath)
    assertEquals(emptyList(), outcome.skipped)
  }

  private fun writePhaseFile(
    name: String,
    content: String,
  ) = writePhaseBytes(name, content.toByteArray())

  private fun writePhaseBytes(
    name: String,
    bytes: ByteArray,
  ) {
    val directory = repoRoot.resolve(".skill-bill/phases")
    Files.createDirectories(directory)
    Files.write(directory.resolve(name), bytes)
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
