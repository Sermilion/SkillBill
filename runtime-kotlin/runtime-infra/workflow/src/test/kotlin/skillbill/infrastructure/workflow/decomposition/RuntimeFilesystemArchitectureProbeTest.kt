package skillbill.infrastructure.workflow.decomposition

import skillbill.error.core.SkillBillRuntimeException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeFilesystemArchitectureProbeTest {
  @Test
  fun `probe recovery rejects staged bytes that differ from recorded digest`() {
    val root = Files.createTempDirectory("skill-bill-248-journal-")
    try {
      val target = root.resolve("spec.md")
      Files.writeString(target, "old")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      Files.writeString(transaction.entries.single().staged, "corrupted")
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertEquals("old", Files.readString(target))
      assertTrue(Files.exists(transaction.marker))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `probe journal cleanup does not trust an unrelated sibling directory`() {
    val root = Files.createTempDirectory("skill-bill-248-journal-owner-")
    try {
      val target = root.resolve("spec.md")
      val unrelated = Files.createDirectory(root.resolve("evidence"))
      Files.writeString(unrelated.resolve("keep.txt"), "keep")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      journal.apply(transaction)
      val marker =
        Files.readString(transaction.marker)
          .replace(transaction.stagingDirectory.toString(), unrelated.toString())
      Files.writeString(transaction.marker, marker)
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertTrue(Files.exists(unrelated.resolve("keep.txt")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
