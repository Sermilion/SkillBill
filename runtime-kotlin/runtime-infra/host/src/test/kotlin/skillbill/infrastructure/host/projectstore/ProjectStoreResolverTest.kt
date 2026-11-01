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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectStoreResolverTest {
  private val home: Path = Files.createTempDirectory("skillbill-project-store-home").toRealPath()
  private val stateDir: Path = home.resolve("state")
  private val storeParent: Path = stateDir.resolve("projects")
  private val lineages = LineagesByRoot()
  private val warnings = mutableListOf<String>()

  @Test
  fun `a linked worktree resolves to the project id of its main checkout`() {
    val main = gitCheckout("main").also { lineages.set(it, LINEAGE_A) }
    val worktree = linkedWorktree(main, "wt").also { lineages.set(it, LINEAGE_A) }

    val fromMain = resolver().resolve(main)
    val fromWorktree = resolver().resolve(worktree)

    assertEquals(fromMain.projectId, fromWorktree.projectId)
    assertEquals(listOf(fromMain.projectId), storeNames())
    assertEquals(listOf("project.json"), Files.list(fromMain.root).use { it.map { p -> "${p.fileName}" }.toList() })
  }

  @Test
  fun `a moved checkout with one missing-path candidate adopts the existing store`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val before = resolver().resolve(original)
    original.toFile().deleteRecursively()
    val moved = gitCheckout("moved").also { lineages.set(it, LINEAGE_A) }

    val after = resolver().resolve(moved)

    assertEquals(before.projectId, after.projectId)
    assertEquals(listOf(before.projectId), storeNames())
  }

  @Test
  fun `a second clone of the same remote shares the original store while both stay present`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val clone = gitCheckout("clone").also { lineages.set(it, LINEAGE_A) }

    val first = resolver().resolve(original)
    val second = resolver().resolve(clone)

    assertEquals(first.projectId, second.projectId)
    assertEquals(listOf(first.projectId), storeNames())
    assertEquals(first.projectId, resolver().resolve(original).projectId)
    assertEquals(first.projectId, resolver().resolve(clone).projectId)
    assertEquals(1, warnings.count { "value_used=lineage_shared matched=${first.projectId}" in it })
  }

  @Test
  fun `a shared store is adopted by a moved checkout only once every recorded checkout is gone`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val clone = gitCheckout("clone").also { lineages.set(it, LINEAGE_A) }
    val shared = resolver().resolve(original)
    resolver().resolve(clone)
    original.toFile().deleteRecursively()
    val moved = gitCheckout("moved").also { lineages.set(it, LINEAGE_A) }

    assertEquals(shared.projectId, resolver().resolve(moved).projectId)
    assertEquals(shared.projectId, resolver().resolve(clone).projectId)
    assertEquals(listOf(shared.projectId), storeNames())
  }

  @Test
  fun `a clone of the same lineage with a different remote gets its own suffixed store`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val fork = gitCheckout("fork").also { lineages.set(it, LINEAGE_A_FORK) }

    val first = resolver().resolve(original)
    val second = resolver().resolve(fork)

    assertEquals("${first.projectId}-2", second.projectId)
    assertEquals(listOf(first.projectId, second.projectId), storeNames())
    assertEquals(first.projectId, resolver().resolve(original).projectId)
  }

  @Test
  fun `a clone whose remote is the same repository in another URL form shares the original store`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val https = gitCheckout("https")
    lineages.set(https, LINEAGE_A.copy(remoteUrl = "https://example.com/org/repo"))

    val first = resolver().resolve(original)

    assertEquals(first.projectId, resolver().resolve(https).projectId)
    assertEquals(listOf(first.projectId), storeNames())
  }

  @Test
  fun `a scratch clone whose remote is a local checkout of the project shares that checkout's store`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val scratch = gitCheckout("scratch").also { lineages.set(it, LINEAGE_A.copy(remoteUrl = original.toString())) }
    val elsewhere = Files.createDirectories(home.resolve("elsewhere"))
    val stray = gitCheckout("stray").also { lineages.set(it, LINEAGE_A.copy(remoteUrl = elsewhere.toString())) }

    val first = resolver().resolve(original)

    assertEquals(first.projectId, resolver().resolve(scratch).projectId)
    val strayId = resolver().resolve(stray).projectId
    assertEquals("${first.projectId}-2", strayId, "A local remote that is no checkout of it stays apart.")
  }

  @Test
  fun `a directory inside the state folder gets a location but no store record`() {
    val installedSkills = Files.createDirectories(stateDir.resolve("installed-skills").resolve("skill-bill"))

    val location = resolver().resolve(installedSkills)

    assertTrue(location.root.startsWith(storeParent))
    val recorded = Files.exists(storeParent) && storeNames().isNotEmpty()
    assertTrue(!recorded, "No store is recorded for Skill Bill's own folders.")
  }

  @Test
  fun `a no-commit repository is re-keyed to its lineage after the first commit`() {
    val repo = gitCheckout("fresh")
    val noCommits = resolver().resolve(repo)
    assertTrue(noCommits.projectId.startsWith("p-"))

    lineages.set(repo, LINEAGE_A)
    val committed = resolver().resolve(repo)

    assertEquals(lineageProjectId(LINEAGE_A), committed.projectId)
    assertEquals(listOf(committed.projectId), storeNames())
  }

  @Test
  fun `lineage probe interruption propagates without creating a fallback store`() {
    val repo = gitCheckout("interrupted")
    val interruptedLineages =
      object : RepositoryLineagePort {
        override fun lineage(repoRoot: Path): RepositoryLineage? = throw InterruptedException("probe interrupted")
      }

    assertFailsWith<InterruptedException> { resolver(interruptedLineages).resolve(repo) }

    assertTrue(!Files.exists(storeParent), "An interrupted git probe must not create a non-git fallback store.")
    assertTrue(
      warnings.none { "git_unavailable" in it },
      "An interruption must not be reported as git unavailable.",
    )
  }

  @Test
  fun `a non-git directory gets a path-hash store`() {
    val plain = Files.createDirectories(home.resolve("plain"))

    val location = resolver().resolve(plain)

    assertEquals(nonGitProjectId(plain), location.projectId)
  }

  @Test
  fun `a malformed record is skipped and kept while resolve still returns a location`() {
    val repo = gitCheckout("main").also { lineages.set(it, LINEAGE_A) }
    val wanted = storeParent.resolve(lineageProjectId(LINEAGE_A))
    Files.createDirectories(wanted)
    Files.writeString(wanted.resolve("project.json"), "{not json")

    val location = resolver().resolve(repo)

    assertEquals("${wanted.fileName}-2", location.projectId)
    assertEquals("{not json", Files.readString(wanted.resolve("project.json")))
    assertTrue(warnings.any { it.startsWith("seam=project_store_record ") })
  }

  @Test
  fun `a legacy skill-bill directory is warned about once per project and never touched`() {
    val repo = gitCheckout("main").also { lineages.set(it, LINEAGE_A) }
    val legacyFile = Files.createDirectories(repo.resolve(".skill-bill")).resolve("config.yaml")
    Files.writeString(legacyFile, "legacy")

    resolver().resolve(repo)
    assertEquals(1, warnings.count { "seam=project_store_legacy_state" in it })
    resolver().resolve(repo)

    assertEquals(1, warnings.count { "seam=project_store_legacy_state" in it })
    assertEquals("legacy", Files.readString(legacyFile))
  }

  @Test
  fun `link attaches the checkout to the target store and the old store stops matching`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val clone = gitCheckout("clone").also { lineages.set(it, LINEAGE_A_FORK) }
    val target = resolver().resolve(original)
    val cloneStore = resolver().resolve(clone)

    val linked = resolver().link(clone, target.projectId)

    assertEquals(target.projectId, linked.projectId)
    assertEquals(target.projectId, resolver().resolve(clone).projectId)
    assertEquals(target.projectId, resolver().resolve(original).projectId)
    val oldRecord = ProjectStoreRecordCodec.parse(Files.readString(cloneStore.root.resolve("project.json")))
    assertEquals(target.projectId, oldRecord.linkedTo)
  }

  @Test
  fun `a linked checkout that moves adopts its link target instead of creating a third store`() {
    val original = gitCheckout("original").also { lineages.set(it, LINEAGE_A) }
    val clone = gitCheckout("clone").also { lineages.set(it, LINEAGE_A_FORK) }
    val target = resolver().resolve(original)
    resolver().resolve(clone)
    resolver().link(clone, target.projectId)
    clone.toFile().deleteRecursively()
    original.toFile().deleteRecursively()
    val moved = gitCheckout("moved").also { lineages.set(it, LINEAGE_A) }

    val after = resolver().resolve(moved)

    assertEquals(target.projectId, after.projectId)
    assertEquals(listOf(target.projectId, "${target.projectId}-2"), storeNames())
  }

  @Test
  fun `link to an unknown store fails naming the id and the store parent`() {
    val repo = gitCheckout("main").also { lineages.set(it, LINEAGE_A) }

    val failure = assertFailsWith<RuntimeException> { resolver().link(repo, "l-missing") }

    assertTrue("l-missing" in failure.message.orEmpty() && storeParent.toString() in failure.message.orEmpty())
  }

  private fun resolver(lineages: RepositoryLineagePort = this.lineages): ProjectStoreResolver =
    ProjectStoreResolver(
      CanonicalRepositoryRoot,
      lineages,
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

  private fun gitCheckout(name: String): Path = Files.createDirectories(home.resolve(name).resolve(".git")).parent

  private fun linkedWorktree(
    main: Path,
    name: String,
  ): Path {
    val adminDir = Files.createDirectories(main.resolve(".git/worktrees/$name"))
    Files.writeString(adminDir.resolve("commondir"), "../..\n")
    val worktree = Files.createDirectories(home.resolve(name))
    Files.writeString(worktree.resolve(".git"), "gitdir: $adminDir\n")
    return worktree
  }

  private fun storeNames(): List<String> =
    Files.list(storeParent).use { entries -> entries.map { it.fileName.toString() }.toList() }.sorted()

  private class LineagesByRoot : RepositoryLineagePort {
    private val byRoot = mutableMapOf<Path, RepositoryLineage>()

    fun set(
      root: Path,
      lineage: RepositoryLineage,
    ) {
      byRoot[root.toRealPath()] = lineage
    }

    override fun lineage(repoRoot: Path): RepositoryLineage? = byRoot[repoRoot.toRealPath()]
  }

  private companion object {
    val LINEAGE_A = RepositoryLineage(listOf("a".repeat(40)), "git@example.com:org/repo.git")
    val LINEAGE_A_FORK = RepositoryLineage(listOf("a".repeat(40)), "git@example.com:fork/repo.git")
  }
}
