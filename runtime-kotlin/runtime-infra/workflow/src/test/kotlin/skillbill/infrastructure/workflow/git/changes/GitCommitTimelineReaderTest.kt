package skillbill.infrastructure.workflow.git.changes

import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workchanges.model.WorkCommitTimelineResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitCommitTimelineReaderTest {
  private val adapter = GitWorkChangesAdapter()
  private lateinit var repo: Path
  private val shas = mutableMapOf<String, String>()

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-commit-timeline")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    commit("main", "main")
    git(repo, "checkout", "-b", "feat")
    commit("first", "first subject\n\nbody line\n\nSkill-Bill-Subtask: SKILL-1/1\nSkill-Bill-Subtask: SKILL-1/2")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `timeline keeps full multi-line message and both trailers`() {
    val listed = assertIs<WorkCommitTimelineResult.Listed>(adapter.commitTimeline(repo.toString(), "main", emptyList()))

    assertEquals(listOf("first subject"), listed.commits.map { it.subject })
    assertTrue("body line" in listed.commits.single().message)
    assertEquals(2, listed.commits.single().message.lines().count { it.startsWith("Skill-Bill-Subtask:") })
    assertEquals(shas.getValue("main"), listed.commits.single().parentSha)
  }

  @Test
  fun `pruned recorded sha is omitted without making the timeline unavailable`() {
    val pruned = shas.getValue("first")
    git(repo, "commit", "--amend", "-m", "rewritten")
    git(repo, "reflog", "expire", "--expire=now", "--all")
    git(repo, "gc", "--prune=now")

    val listed =
      assertIs<WorkCommitTimelineResult.Listed>(
        adapter.commitTimeline(repo.toString(), "main", listOf(pruned)),
      )

    assertTrue(pruned !in listed.reachableRecordedShas)
  }

  @Test
  fun `detached head has no current branch`() {
    git(repo, "checkout", "--detach", "HEAD")

    val listed = assertIs<WorkCommitTimelineResult.Listed>(adapter.commitTimeline(repo.toString(), "main", emptyList()))

    assertNull(listed.currentBranch)
  }

  @Test
  fun `saved branch timeline does not depend on the checked out branch`() {
    git(repo, "checkout", "main")

    val listed =
      assertIs<WorkCommitTimelineResult.Listed>(
        adapter.commitTimeline(repo.toString(), "main", listOf(shas.getValue("first")), "feat"),
      )

    assertEquals(shas.getValue("first"), listed.headSha)
    assertEquals(listOf(shas.getValue("first")), listed.commits.map { it.sha })
    assertTrue(shas.getValue("first") in listed.reachableRecordedShas)
  }

  private fun commit(
    name: String,
    message: String,
  ) {
    repo.resolve("$name.txt").writeText("$name\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", message)
    shas[name] = git(repo, "rev-parse", "HEAD")
  }
}
