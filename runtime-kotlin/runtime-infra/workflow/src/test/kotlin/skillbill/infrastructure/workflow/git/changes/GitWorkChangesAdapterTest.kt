package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import skillbill.ports.workchanges.model.WorkFileDiffResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitWorkChangesAdapterTest {
  private val adapter = GitWorkChangesAdapter()
  private lateinit var repo: Path
  private lateinit var base: String

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-work-changes")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    write("a.kt", (1..10).joinToString("") { "line$it\n" })
    write("mod.txt", "x\n")
    write("del.txt", "gone\n")
    write("keep.txt", "keep\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "base")
    base = git(repo, "rev-parse", "HEAD")

    git(repo, "mv", "a.kt", "b.kt")
    write("b.kt", (1..9).joinToString("") { "line$it\n" } + "changed10\nnew11\nnew12\n")
    write("mod.txt", "x\ny\n")
    repo.resolve("bin.dat").writeBytes(byteArrayOf(1, 0, 2, 0, 3))
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "work")

    Files.delete(repo.resolve("del.txt"))
    write("keep.txt", "keep\nmore\n")
    write("u.txt", "a\nb")
    write("notes.txt", "baseline scratch\n")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `changes lists commits since the base, uncommitted edits and untracked files`() {
    val result = adapter.changes(WorkChangeBaseline.SubtaskStart(repo.toString(), base, listOf("notes.txt")))

    val listed = assertIs<WorkChangeListResult.Listed>(result)
    assertEquals(base, listed.baseSha)
    assertEquals(
      listOf(
        WorkChangedFile("b.kt", "a.kt", WorkChangeStatus.RENAMED, 3, 1),
        WorkChangedFile("bin.dat", null, WorkChangeStatus.ADDED, null, null),
        WorkChangedFile("del.txt", null, WorkChangeStatus.DELETED, 0, 1),
        WorkChangedFile("keep.txt", null, WorkChangeStatus.MODIFIED, 1, 0),
        WorkChangedFile("mod.txt", null, WorkChangeStatus.MODIFIED, 1, 0),
        WorkChangedFile("u.txt", null, WorkChangeStatus.UNTRACKED, 2, 0),
      ),
      listed.files,
    )
    assertEquals(7, listed.insertions)
    assertEquals(2, listed.deletions)
  }

  @Test
  fun `fileDiff returns patches for untracked and renamed files and Binary for binary files`() {
    val listed =
      assertIs<WorkChangeListResult.Listed>(
        adapter.changes(WorkChangeBaseline.RunStart(repo.toString(), base, emptyList())),
      )
    val byPath = listed.files.associateBy(WorkChangedFile::path)

    val untracked = assertIs<WorkFileDiffResult.Patch>(diff(byPath.getValue("u.txt")))
    assertEquals(2, untracked.text.lines().count { it.startsWith("+") && !it.startsWith("+++") })

    val renamed = assertIs<WorkFileDiffResult.Patch>(diff(byPath.getValue("b.kt")))
    assertTrue("rename from a.kt" in renamed.text, renamed.text)
    assertEquals(3, renamed.text.lines().count { it.startsWith("+") && !it.startsWith("+++") })
    assertEquals(1, renamed.text.lines().count { it.startsWith("-") && !it.startsWith("---") })

    assertEquals(WorkFileDiffResult.Binary, diff(byPath.getValue("bin.dat")))
  }

  @Test
  fun `an unreachable base is BaseUnavailable for both reads`() {
    val missing = "0123456789abcdef0123456789abcdef01234567"
    val file = WorkChangedFile("mod.txt", null, WorkChangeStatus.MODIFIED, 1, 0)

    assertIs<WorkChangeListResult.BaseUnavailable>(
      adapter.changes(WorkChangeBaseline.SubtaskStart(repo.toString(), missing, emptyList())),
    )
    assertIs<WorkFileDiffResult.BaseUnavailable>(adapter.fileDiff(repo.toString(), missing, file))
  }

  private fun diff(file: WorkChangedFile): WorkFileDiffResult = adapter.fileDiff(repo.toString(), base, file)

  private fun write(
    relative: String,
    content: String,
  ) {
    repo.resolve(relative).writeText(content)
  }
}
