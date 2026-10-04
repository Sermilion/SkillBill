package skillbill.infrastructure.workflow.decomposition

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecompositionManifestBundleJournalValidationTest {
  @Test
  fun `recovery through a symlinked ancestor accepts missing targets and applied staged files`() {
    val root = Files.createTempDirectory("bundle-journal-parent-alias")
    try {
      val realDirectory = Files.createDirectory(root.resolve("real"))
      val alias = Files.createSymbolicLink(root.resolve("alias"), realDirectory)
      val firstTarget = alias.resolve("first.md")
      val secondTarget = alias.resolve("second.md")
      val journal = DecompositionManifestBundleJournal()
      val transaction =
        journal.create(
          alias,
          listOf(firstTarget to "first", secondTarget to "second"),
        )
      journal.apply(transaction.copy(entries = listOf(transaction.entries.first())))

      journal.recoverPending(alias)

      assertEquals("first", Files.readString(realDirectory.resolve("first.md")))
      assertEquals("second", Files.readString(realDirectory.resolve("second.md")))
      assertFalse(Files.exists(transaction.marker))
      assertFalse(Files.exists(transaction.stagingDirectory))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects staged bytes that differ from recorded digest`() {
    val root = Files.createTempDirectory("bundle-journal-staged-digest")
    try {
      val firstTarget = root.resolve("first.md")
      val secondTarget = root.resolve("second.md")
      Files.writeString(firstTarget, "old-first")
      Files.writeString(secondTarget, "old-second")
      val journal = DecompositionManifestBundleJournal()
      val transaction =
        journal.create(
          root,
          listOf(firstTarget to "intended-first", secondTarget to "intended-second"),
        )
      Files.writeString(transaction.entries[1].staged, "corrupted")
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertEquals("old-first", Files.readString(firstTarget))
      assertEquals("old-second", Files.readString(secondTarget))
      assertTrue(Files.exists(transaction.marker))
      assertTrue(Files.exists(transaction.stagingDirectory))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects journal that names unrelated sibling staging directory`() {
    val root = Files.createTempDirectory("bundle-journal-sibling-staging")
    try {
      val target = root.resolve("spec.md")
      val unrelated = Files.createDirectory(root.resolve("evidence"))
      Files.writeString(unrelated.resolve("keep.txt"), "keep")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      journal.apply(transaction)
      val markerText =
        Files.readString(transaction.marker)
          .replace(transaction.stagingDirectory.toString(), unrelated.toString())
      Files.writeString(transaction.marker, markerText)
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertTrue(Files.exists(unrelated))
      assertTrue(Files.exists(unrelated.resolve("keep.txt")))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects duplicate normalized targets`() {
    val root = Files.createTempDirectory("bundle-journal-duplicate-target")
    try {
      val target = root.resolve("spec.md")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "first", target to "second"))
      val markerText = Files.readString(transaction.marker)
      Files.writeString(transaction.marker, markerText)
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertTrue(!Files.exists(target) || !Files.readString(target).contains("second"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects duplicate staged paths before moving either target`() {
    val root = Files.createTempDirectory("bundle-journal-duplicate-staged")
    try {
      val firstTarget = root.resolve("first.md")
      val secondTarget = root.resolve("second.md")
      Files.writeString(firstTarget, "old-first")
      Files.writeString(secondTarget, "old-second")
      val journal = DecompositionManifestBundleJournal()
      val transaction =
        journal.create(
          root,
          listOf(firstTarget to "new-first", secondTarget to "new-second"),
        )
      Files.writeString(
        transaction.marker,
        Files.readString(transaction.marker).replace(
          transaction.entries[1].staged.toString(),
          transaction.entries[0].staged.toString(),
        ),
      )

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          journal.recoverPending(root)
        }

      assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_DUPLICATE_STAGED, failure.code)
      assertEquals("old-first", Files.readString(firstTarget))
      assertEquals("old-second", Files.readString(secondTarget))
      assertTrue(Files.exists(transaction.marker))
      assertTrue(Files.exists(transaction.stagingDirectory))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects staging traversal before touching sibling evidence`() {
    val root = Files.createTempDirectory("bundle-journal-traversal")
    val sibling = Files.createTempDirectory("bundle-journal-sibling")
    try {
      val target = root.resolve("spec.md")
      Files.writeString(target, "old")
      Files.writeString(sibling.resolve("keep.txt"), "keep")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      val traversingDirectory = root.resolve("../${sibling.fileName}")
      Files.writeString(
        transaction.marker,
        Files.readString(transaction.marker).replace(
          transaction.stagingDirectory.toString(),
          traversingDirectory.toString(),
        ),
      )

      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertEquals("keep", Files.readString(sibling.resolve("keep.txt")))
      assertEquals("old", Files.readString(target))
    } finally {
      root.toFile().deleteRecursively()
      sibling.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects symlink escape from staging directory`() {
    val root = Files.createTempDirectory("bundle-journal-symlink-escape")
    try {
      val outside = Files.createTempDirectory("bundle-journal-outside")
      val outsideFile = outside.resolve("escaped.txt")
      Files.writeString(outsideFile, "escaped")
      val target = root.resolve("spec.md")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      val symlink = transaction.stagingDirectory.resolve("entry-link")
      Files.deleteIfExists(transaction.entries.single().staged)
      Files.createSymbolicLink(symlink, outsideFile)
      val markerText =
        Files.readString(transaction.marker)
          .replace(transaction.entries.single().staged.toString(), symlink.toString())
      Files.writeString(transaction.marker, markerText)
      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      outside.toFile().deleteRecursively()
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects a target symlink that resolves outside the marker parent`() {
    val root = Files.createTempDirectory("bundle-journal-target-symlink")
    val outside = Files.createTempDirectory("bundle-journal-target-outside")
    try {
      val outsideFile = outside.resolve("escaped.txt")
      Files.writeString(outsideFile, "outside")
      val target = root.resolve("spec.md")
      val targetLink = root.resolve("linked.md")
      Files.createSymbolicLink(targetLink, outsideFile)
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      val markerText =
        Files.readString(transaction.marker)
          .replace(target.toString(), targetLink.toString())
      Files.writeString(transaction.marker, markerText)

      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertEquals("outside", Files.readString(outsideFile))
      assertTrue(Files.exists(transaction.marker))
    } finally {
      root.toFile().deleteRecursively()
      outside.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects malformed entry fields as typed failures`() {
    val root = Files.createTempDirectory("bundle-journal-malformed-field")
    try {
      val target = root.resolve("spec.md")
      Files.writeString(target, "old")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      val markerText =
        Files.readString(transaction.marker).replace(
          "sha256:",
          "sha256: not-a-digest\n  ignored:",
        )
      Files.writeString(transaction.marker, markerText)

      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          journal.recoverPending(root)
        }
      assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_INVALID, failure.code)
      assertEquals("old", Files.readString(target))
      assertTrue(Files.exists(transaction.marker))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects unsupported contract version and retains marker`() {
    val root = Files.createTempDirectory("bundle-journal-unsupported-version")
    try {
      val target = root.resolve("spec.md")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      val yamlMapper = YAMLMapper()
      val markerNode = yamlMapper.readTree(Files.readString(transaction.marker)) as ObjectNode
      markerNode.put(SharedPayloadKeys.CONTRACT_VERSION, "9.9")
      Files.writeString(transaction.marker, yamlMapper.writeValueAsString(markerNode))
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          journal.recoverPending(root)
        }
      assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_UNSUPPORTED_CONTRACT_VERSION, failure.code)
      assertTrue(failure.message.orEmpty().contains("Back up the marker"))
      assertTrue(!Files.exists(target))
      assertTrue(Files.exists(transaction.marker))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `recovery rejects a tampered already-applied target and retains evidence`() {
    val root = Files.createTempDirectory("bundle-journal-applied-digest")
    try {
      val target = root.resolve("spec.md")
      Files.writeString(target, "old")
      val journal = DecompositionManifestBundleJournal()
      val transaction = journal.create(root, listOf(target to "intended"))
      journal.apply(transaction)
      Files.writeString(target, "tampered")

      assertFailsWith<SkillBillRuntimeException> {
        journal.recoverPending(root)
      }
      assertEquals("tampered", Files.readString(target))
      assertTrue(Files.exists(transaction.marker))
      assertTrue(Files.exists(transaction.stagingDirectory))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `partial apply then restart moves only pending entries`() {
    val root = Files.createTempDirectory("bundle-journal-partial-restart")
    try {
      val firstTarget = root.resolve("first.md")
      val secondTarget = root.resolve("second.md")
      Files.writeString(firstTarget, "old-first")
      Files.writeString(secondTarget, "old-second")
      val journal = DecompositionManifestBundleJournal()
      val transaction =
        journal.create(
          root,
          listOf(firstTarget to "new-first", secondTarget to "new-second"),
        )
      journal.apply(
        DecompositionManifestBundleTransaction(
          transaction.marker,
          transaction.stagingDirectory,
          listOf(transaction.entries.first()),
        ),
      )
      assertEquals("new-first", Files.readString(firstTarget))
      assertEquals("old-second", Files.readString(secondTarget))
      journal.recoverPending(root)
      assertEquals("new-second", Files.readString(secondTarget))
      assertFalse(Files.exists(transaction.marker))
      assertFalse(Files.exists(transaction.stagingDirectory))
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
