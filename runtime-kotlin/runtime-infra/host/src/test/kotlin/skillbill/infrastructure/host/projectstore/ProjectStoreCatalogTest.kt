package skillbill.infrastructure.host.projectstore

import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.projectstore.model.RepositoryLineage
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreCatalogTest {
  private val home: Path = Files.createTempDirectory("skillbill-project-catalog-home").toRealPath()
  private val stateDir: Path = home.resolve("state")
  private val storeParent: Path = stateDir.resolve("projects")
  private val warnings = mutableListOf<String>()

  @Test
  fun `catalog skips malformed records, maps git dirs to working trees and orders newest first with unparsable last`() {
    val repo = Files.createDirectories(home.resolve("repo").resolve(".git")).parent
    val plain = Files.createDirectories(home.resolve("plain"))
    val gone = home.resolve("gone").resolve(".git")
    writeRecord("alpha", "2026-01-01T00:00:00Z", repo.resolve(".git"), listOf(gone))
    writeRecord("beta", "2026-02-01T00:00:00Z", plain, emptyList())
    writeRecord("gamma", "not-a-time", plain, emptyList())
    val broken = Files.createDirectories(storeParent.resolve("broken"))
    Files.writeString(broken.resolve("project.json"), "{nope")

    val entries = catalog().list()

    assertEquals(listOf("beta", "alpha", "gamma"), entries.map { it.projectId })
    assertEquals(listOf(repo), entries.single { it.projectId == "alpha" }.checkoutRoots)
    assertEquals(listOf(plain), entries.single { it.projectId == "beta" }.checkoutRoots)
    assertEquals(storeParent.resolve("alpha").resolve("specs"), entries.first { it.projectId == "alpha" }.specsRoot)
    assertEquals(Instant.parse("2026-02-01T00:00:00Z"), entries.first().lastSeen)
    assertNull(entries.last().lastSeen)
    assertTrue(warnings.any { it.startsWith("seam=project_store_record ") && "broken" in it })
  }

  @Test
  fun `catalog reports whether a store holds spec bundles and the checkout it was last recorded at`() {
    val gone = home.resolve("gone-clone").resolve(".git")
    writeRecord("with-bundle", "2026-01-01T00:00:00Z", gone, emptyList())
    writeRecord("files-only", "2026-01-01T00:00:00Z", gone, emptyList())
    Files.createDirectories(storeParent.resolve("with-bundle").resolve("specs").resolve("SKILL-1-thing"))
    Files.createDirectories(storeParent.resolve("files-only").resolve("specs"))
    Files.writeString(storeParent.resolve("files-only").resolve("specs").resolve("notes.md"), "notes")

    val entries = catalog().list().associateBy { it.projectId }

    assertEquals(true, entries.getValue("with-bundle").hasSpecs)
    assertEquals(false, entries.getValue("files-only").hasSpecs)
    assertEquals(home.resolve("gone-clone"), entries.getValue("with-bundle").lastCheckoutRoot)
    assertEquals(emptyList(), entries.getValue("with-bundle").checkoutRoots)
  }

  private fun writeRecord(
    id: String,
    lastSeen: String,
    commonDir: Path,
    sharedCommonDirs: List<Path>,
  ) {
    val record =
      ProjectStoreRecord(
        projectId = id,
        keyKind = ProjectKeyKind.NON_GIT_PATH,
        commonDir = commonDir.toString(),
        rootCommits = emptyList(),
        remoteUrl = null,
        lastSeen = lastSeen,
        sharedCommonDirs = sharedCommonDirs.map(Path::toString),
      )
    val dir = Files.createDirectories(storeParent.resolve(id))
    Files.writeString(dir.resolve("project.json"), ProjectStoreRecordCodec.encode(record))
  }

  private fun catalog(): ProjectStoreResolver =
    ProjectStoreResolver(
      CanonicalRepositoryRoot,
      object : RepositoryLineagePort {
        override fun lineage(repoRoot: Path): RepositoryLineage? = null
      },
      object : RuntimeDiagnostics {
        override fun info(message: String) = Unit

        override fun warning(
          message: String,
          error: Throwable?,
        ) {
          warnings += message
        }

        override fun error(
          message: String,
          error: Throwable?,
        ) = Unit
      },
      EnvironmentContext(environment = mapOf(STATE_DIR_ENVIRONMENT_KEY to stateDir.toString()), userHome = home),
      Clock.systemUTC(),
    )
}
