package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workchanges.model.WorkCommitRange
import skillbill.ports.workchanges.model.WorkOutsideCommitsResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitOutsideCommitReaderTest {
  private val adapter = GitWorkChangesAdapter()
  private lateinit var repo: Path
  private val shas = mutableMapOf<String, String>()

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-outside-commits")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    commit("main")
    git(repo, "checkout", "-b", "feat")
    listOf("subtask-1", "operator", "subtask-2", "finalize", "subtask-3", "subtask-3-more").forEach(::commit)
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `outsideCommits returns only commits no subtask range contains, oldest first`() {
    val ranges =
      listOf(
        WorkCommitRange(shas.getValue("main"), shas.getValue("subtask-1")),
        WorkCommitRange(shas.getValue("operator"), shas.getValue("subtask-2")),
        WorkCommitRange(shas.getValue("finalize"), null),
      )

    val listed = assertIs<WorkOutsideCommitsResult.Listed>(adapter.outsideCommits(repo.toString(), "main", ranges))

    assertEquals(shas.getValue("main"), listed.mergeBaseSha)
    assertEquals(listOf("operator", "finalize"), listed.commits.map { it.subject })
    assertEquals(
      listOf(listOf("operator.txt"), listOf("finalize.txt")),
      listed.commits.map { commit -> commit.files.map { it.path } },
    )
    assertEquals(shas.getValue("subtask-1"), listed.commits.first().parentSha)
  }

  @Test
  fun `outsideCommits with a missing range start is BaseUnavailable naming it`() {
    val missing = "0123456789abcdef0123456789abcdef01234567"

    val result = adapter.outsideCommits(repo.toString(), "main", listOf(WorkCommitRange(missing, null)))

    val unavailable = assertIs<WorkOutsideCommitsResult.BaseUnavailable>(result)
    assertTrue(missing in unavailable.reason, unavailable.reason)
  }

  private fun commit(name: String) {
    repo.resolve("$name.txt").writeText("$name\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", name)
    shas[name] = git(repo, "rev-parse", "HEAD")
  }
}
