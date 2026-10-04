package skillbill.infrastructure.workflow.git.checkpoint

import skillbill.infrastructure.workflow.git.git
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CHECKPOINT_PREFIX = "refs/skill-bill/checkpoints/"

class GitCheckpointHistoryOperationsTest {
  private lateinit var repo: Path

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-checkpoint-history")
    git("init", "--initial-branch", "main")
    git("config", "user.email", "runtime@skill-bill.test")
    git("config", "user.name", "Skill Bill Runtime")
    git("config", "commit.gpgsign", "false")
    write("owned/Base.kt", "base\n")
    write("foreign/Other.kt", "other\n")
    git("add", "-A")
    git("commit", "-m", "base")
    git("checkout", "-b", "feat/checkpoint-history")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `amend commits only the index and leaves an unstaged edit unstaged`() {
    val before = head()
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")
    write("foreign/Other.kt", "edited by someone else\n")

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, before)

    assertTrue(amended is WorkflowGitOperationResult.Ok, amended.error)
    assertEquals(head(), amended.value.trim())
    assertTrue(head() != before, "amend must rewrite HEAD")
    assertEquals("amended\n", showAtHead("owned/Base.kt"))
    assertEquals("other\n", showAtHead("foreign/Other.kt"))
    assertEquals(
      emptyList(),
      runGitCommand(repo, "diff", "--cached", "--name-only").value.orEmpty().lines().filter(String::isNotBlank),
      "the amend must consume the index and stage nothing further",
    )
    assertEquals(
      listOf("foreign/Other.kt"),
      runGitCommand(repo, "diff", "--name-only").value.orEmpty().lines().filter(String::isNotBlank),
    )
  }

  @Test
  fun `amend with an empty index fails and leaves HEAD unchanged`() {
    val before = head()

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, before)

    assertFalse(amended is WorkflowGitOperationResult.Ok)
    assertEquals(before, head())
  }

  @Test
  fun `amend refuses while HEAD sits on a protected branch`() {
    git("checkout", "main")
    val before = head()
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, before)

    assertFalse(amended is WorkflowGitOperationResult.Ok)
    assertContains(amended.error.orEmpty(), "protected branch 'main'")
    assertEquals(before, head())
  }

  @Test
  fun `amend refuses when HEAD is not the caller-owned commit`() {
    val before = head()
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")

    val amended = GitCheckpointHistoryOperations.amendHeadCommit(repo, "0".repeat(before.length))

    assertFalse(amended is WorkflowGitOperationResult.Ok)
    assertEquals(before, head())
  }

  @Test
  fun `a checkpoint ref round-trips write, resolve, list and idempotent delete`() {
    val sha = head()
    val ref = "${CHECKPOINT_PREFIX}subtask-1/städte-checkpoint"

    assertTrue(
      GitCheckpointHistoryOperations.updateCheckpointRef(repo, CHECKPOINT_PREFIX, ref, sha) is
        WorkflowGitOperationResult.Ok,
    )
    assertEquals(sha, GitCheckpointHistoryOperations.resolveCheckpointRef(repo, CHECKPOINT_PREFIX, ref).value.trim())
    assertEquals(listOf(ref), listedRefs())

    assertTrue(
      GitCheckpointHistoryOperations.deleteCheckpointRef(repo, CHECKPOINT_PREFIX, ref) is
        WorkflowGitOperationResult.Ok,
    )
    assertTrue(
      GitCheckpointHistoryOperations.deleteCheckpointRef(repo, CHECKPOINT_PREFIX, ref) is
        WorkflowGitOperationResult.Ok,
      "a repeated delete must stay idempotent so an interrupted prune can re-run",
    )
    assertEquals(emptyList(), listedRefs(), "an emptied namespace must list no refs rather than one blank name")
    val absent = GitCheckpointHistoryOperations.resolveCheckpointRef(repo, CHECKPOINT_PREFIX, ref)
    assertTrue(
      absent is WorkflowGitOperationResult.Ok,
      "an absent ref must resolve ok so callers can tell it from a failed lookup",
    )
    assertEquals("", absent.value.trim())
  }

  @Test
  fun `ref operations reject a name outside the namespace prefix and leave it untouched`() {
    val sha = head()

    val update = GitCheckpointHistoryOperations.updateCheckpointRef(repo, CHECKPOINT_PREFIX, "refs/heads/main", sha)
    val delete = GitCheckpointHistoryOperations.deleteCheckpointRef(repo, CHECKPOINT_PREFIX, "refs/heads/main")

    val siblingPrefix =
      GitCheckpointHistoryOperations.updateCheckpointRef(
        repo,
        CHECKPOINT_PREFIX,
        CHECKPOINT_PREFIX.trimEnd('/') + "-tmp/a",
        sha,
      )

    assertFalse(update is WorkflowGitOperationResult.Ok)
    assertFalse(delete is WorkflowGitOperationResult.Ok)
    assertFalse(siblingPrefix is WorkflowGitOperationResult.Ok)
    assertEquals(sha, runGitCommand(repo, "rev-parse", "refs/heads/main").value.trim())
    assertEquals(emptyList(), listedRefs())
  }

  @Test
  fun `deleting under a subtask prefix sweeps that subtask and reports how many refs went`() {
    val sha = head()
    val subtaskPrefix = "${CHECKPOINT_PREFIX}SKILL-190/3/"
    val refs = listOf("${subtaskPrefix}0", "${subtaskPrefix}1", "${CHECKPOINT_PREFIX}SKILL-190/4/0")
    refs.forEach { ref ->
      assertTrue(
        GitCheckpointHistoryOperations.updateCheckpointRef(repo, CHECKPOINT_PREFIX, ref, sha) is
          WorkflowGitOperationResult.Ok,
      )
    }
    assertEquals(refs, listedRefs().sorted(), "every created ref must list by its full name, one entry each")

    val swept = GitCheckpointHistoryOperations.deleteCheckpointRefsUnderPrefix(repo, CHECKPOINT_PREFIX, subtaskPrefix)

    assertTrue(swept is WorkflowGitOperationResult.Ok, swept.error)
    assertEquals("2", swept.value.trim())
    assertEquals(listOf("${CHECKPOINT_PREFIX}SKILL-190/4/0"), listedRefs(), "a sibling subtask must survive the sweep")
  }

  @Test
  fun `a pre-amend ref keeps the discarded commit reachable while git log never lists it`() {
    val preAmend = head()
    val ref = "${CHECKPOINT_PREFIX}SKILL-190/3/0"
    assertTrue(
      GitCheckpointHistoryOperations.updateCheckpointRef(repo, CHECKPOINT_PREFIX, ref, preAmend) is
        WorkflowGitOperationResult.Ok,
    )
    write("owned/Base.kt", "amended\n")
    git("add", "--", "owned/Base.kt")

    assertTrue(GitCheckpointHistoryOperations.amendHeadCommit(repo, preAmend) is WorkflowGitOperationResult.Ok)

    assertTrue(head() != preAmend, "the amend must have rewritten HEAD")
    assertEquals(
      preAmend,
      GitCheckpointHistoryOperations.resolveCheckpointRef(repo, CHECKPOINT_PREFIX, ref).value.trim(),
      "the pre-amend commit must stay reachable through its checkpoint ref",
    )
    assertEquals(
      listOf(head()),
      runGitCommand(repo, "log", "--format=%H").value.orEmpty().lines().filter(String::isNotBlank),
      "checkpoint refs must not appear in branch history",
    )
  }

  @Test
  fun `the HEAD commit message read returns the full body including its trailer`() {
    write("owned/Base.kt", "trailered\n")
    git("add", "--", "owned/Base.kt")
    git("commit", "-m", "SKILL-190: subtask work\n\nphase=audit generation=0\n\nSkill-Bill-Subtask: SKILL-190/3\n")

    val message = GitCheckpointHistoryOperations.headCommitMessage(repo)

    assertTrue(message is WorkflowGitOperationResult.Ok, message.error)
    assertContains(message.value, "Skill-Bill-Subtask: SKILL-190/3")
    assertContains(message.value, "phase=audit generation=0")
  }

  private fun listedRefs(): List<String> =
    assertIs<WorkflowGitNameListResult.Listed>(
      GitCheckpointHistoryOperations.listCheckpointRefs(repo, CHECKPOINT_PREFIX),
    ).names

  private fun head(): String = runGitCommand(repo, "rev-parse", "HEAD").value.orEmpty().trim()

  private fun showAtHead(relative: String): String = runGitProcess(repo, listOf("show", "HEAD:$relative")).output + "\n"

  private fun write(
    relative: String,
    content: String,
  ) {
    val target = repo.resolve(relative)
    target.parent?.createDirectories()
    target.writeText(content)
  }

  private fun git(vararg args: String) {
    val result = runGitCommand(repo, *args)
    check(result is WorkflowGitOperationResult.Ok) { "git ${args.joinToString(" ")} failed: ${result.error}" }
  }
}
