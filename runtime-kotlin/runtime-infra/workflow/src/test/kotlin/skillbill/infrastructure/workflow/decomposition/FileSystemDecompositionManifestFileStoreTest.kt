package skillbill.infrastructure.workflow.decomposition

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemDecompositionManifestFileStoreTest {
  @Test
  fun `manifest discovery keeps interprocess lock artifacts outside the repository`() {
    val repoRoot = Files.createTempDirectory("decomposition-manifest-discovery")
    val featureSpecsRoot = repoRoot.resolve(".feature-specs")
    val manifest = featureSpecsRoot.resolve("feature").resolve("decomposition-manifest.yaml")
    Files.createDirectories(requireNotNull(manifest.parent))
    Files.writeString(manifest, "contract_version: '0.5'\n")

    val store: DecompositionManifestStore = FileSystemDecompositionManifestFileStore()

    assertEquals(listOf(manifest), store.findDecompositionManifestFiles(repoRoot))
    assertFalse(
      Files.walk(featureSpecsRoot).use { paths ->
        paths.anyMatch { path -> path.fileName.toString() == BUNDLE_LOCK_FILE_NAME }
      },
    )
  }

  @Test
  fun `reading a bundle target recovers a journal left after a partial commit`() {
    val parent = Files.createTempDirectory("decomposition-manifest-bundle-recovery")
    val firstTarget = parent.resolve("spec.md")
    val secondTarget = parent.resolve("decomposition-manifest.yaml")
    Files.writeString(firstTarget, "old spec")
    Files.writeString(secondTarget, "old manifest")

    val transactionId = "test-transaction"
    val stagingDirectory = parent.resolve(".decomposition-manifest-bundle-$transactionId.staging")
    Files.createDirectories(stagingDirectory)
    val firstStaged = stagingDirectory.resolve("entry-0")
    val secondStaged = stagingDirectory.resolve("entry-1")
    Files.writeString(firstStaged, "new spec")
    Files.writeString(secondStaged, "new manifest")
    Files.move(firstStaged, firstTarget, REPLACE_EXISTING)

    val marker = parent.resolve(".decomposition-manifest-bundle-$transactionId.commit")
    Files.writeString(
      marker,
      YAMLMapper().writeValueAsString(
        mapOf(
          "contract_version" to "0.1",
          "staging_directory" to stagingDirectory.toString(),
          "entries" to
            listOf(
              mapOf(
                "target" to firstTarget.toString(),
                "staged" to firstStaged.toString(),
                "sha256" to sha256("new spec"),
              ),
              mapOf(
                "target" to secondTarget.toString(),
                "staged" to secondStaged.toString(),
                "sha256" to sha256("new manifest"),
              ),
            ),
        ),
      ),
    )

    val store: DecompositionManifestStore = FileSystemDecompositionManifestFileStore()

    assertEquals("new manifest", store.readText(secondTarget))
    assertEquals("new spec", Files.readString(firstTarget))
    assertFalse(Files.exists(marker))
    assertFalse(Files.exists(stagingDirectory))
  }

  @Test
  fun `read-only discovery rejects pending bundle journals without recovery`() {
    val repoRoot = Files.createTempDirectory("decomposition-manifest-no-recovery")
    val parent = repoRoot.resolve(".feature-specs/SKILL-901-goal")
    Files.createDirectories(parent)
    val marker = parent.resolve(".decomposition-manifest-bundle-pending.commit")
    Files.writeString(marker, "pending")
    val store = FileSystemDecompositionManifestFileStore()

    assertFailsWith<SkillBillRuntimeException> {
      store.findDecompositionManifestFilesWithoutRecovery(repoRoot)
    }

    assertTrue(Files.exists(marker))
  }

  @Test
  fun `verification failure restores the previous filesystem bundle`() {
    val parent = Files.createTempDirectory("decomposition-manifest-bundle-rollback")
    val firstTarget = parent.resolve("spec.md")
    val secondTarget = parent.resolve("decomposition-manifest.yaml")
    Files.writeString(firstTarget, "old spec")
    Files.writeString(secondTarget, "old manifest")
    val store: DecompositionManifestStore = FileSystemDecompositionManifestFileStore()

    assertFailsWith<IllegalStateException> {
      store.writeBundleAtomically(
        listOf(firstTarget to "new spec", secondTarget to "new manifest"),
      ) {
        val markers =
          Files.list(parent).use { paths ->
            paths.iterator().asSequence()
              .filter { path -> path.fileName.toString().endsWith(".commit") }
              .toList()
          }
        assertEquals(1, markers.size)
        error("verification failed")
      }
    }

    assertEquals("old spec", Files.readString(firstTarget))
    assertEquals("old manifest", Files.readString(secondTarget))
    assertFalse(
      Files.list(parent).use { paths ->
        paths.iterator().asSequence().any { path ->
          path.fileName.toString().contains("decomposition-manifest-bundle-")
        }
      },
    )
  }

  private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { byte -> "%02x".format(byte) }

  private companion object {
    const val BUNDLE_LOCK_FILE_NAME = ".decomposition-manifest-bundle.lock"
  }
}
