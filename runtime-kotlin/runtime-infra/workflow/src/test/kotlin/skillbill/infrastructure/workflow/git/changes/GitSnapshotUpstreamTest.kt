package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.workchanges.model.WorkChangeListResult
import skillbill.ports.workchanges.model.WorkFileDiffResult
import skillbill.ports.workchanges.model.WorkTreeComparisonResult
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitSnapshotUpstreamTest {
  private val repo = Files.createTempDirectory("chat-upstream")
  private val adapter = GitWorkTreeSnapshotAdapter()
  private val changes = GitWorkChangesAdapter()

  @AfterTest
  fun cleanUp() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `comparison retains a chat commit across an upstream merge without changing refs or index`() {
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "test@example.com")
    git(repo, "config", "user.name", "Test")
    val text = (1..30).joinToString("") { "line $it\n" }
    repo.resolve("file.txt").writeText(text)
    git(repo, "add", ".")
    git(repo, "commit", "-m", "base")
    val baseline = git(repo, "rev-parse", "HEAD")
    git(repo, "checkout", "-b", "incoming")
    repo.resolve("file.txt").writeText(text.replace("line 2\n", "upstream line\n"))
    git(repo, "add", ".")
    git(repo, "commit", "-m", "upstream")
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
    git(repo, "checkout", "main")
    repo.resolve("file.txt").writeText(text.replace("line 25\n", "chat line\n"))
    git(repo, "add", ".")
    git(repo, "commit", "-m", "chat edit")
    val beforePull =
      assertIs<WorkTreeComparisonResult.Resolved>(adapter.comparisonBaseline(repo.toString(), baseline, null))
    assertEquals(baseline, beforePull.baseSha)
    git(repo, "merge", "--no-edit", "incoming")
    val refs = git(repo, "show-ref")
    val index = git(repo, "write-tree")

    val comparison =
      assertIs<WorkTreeComparisonResult.Resolved>(adapter.comparisonBaseline(repo.toString(), baseline, null))
    val listed =
      assertIs<WorkChangeListResult.Listed>(
        changes.changes(WorkChangeBaseline.RunStart(repo.toString(), comparison.baseSha, emptyList())),
      )
    assertEquals(1, listed.insertions)
    assertEquals(1, listed.deletions)
    val patch =
      assertIs<WorkFileDiffResult.Patch>(
        changes.fileDiff(repo.toString(), listed.baseSha, listed.files.single()),
      ).text
    assertTrue("+chat line" in patch)
    assertFalse("+upstream line" in patch)
    assertEquals(refs, git(repo, "show-ref"))
    assertEquals(index, git(repo, "write-tree"))
    assertEquals("", git(repo, "status", "--porcelain"))
    assertEquals(comparison, adapter.comparisonBaseline(repo.toString(), baseline, null))
  }
}
