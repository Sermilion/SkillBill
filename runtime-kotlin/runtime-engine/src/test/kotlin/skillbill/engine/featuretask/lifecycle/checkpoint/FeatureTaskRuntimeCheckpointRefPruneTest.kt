package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.pruneEligibleCheckpointRefsForManifest
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimeCheckpointRefPruneTest {
  private lateinit var repo: Path
  private val git: WorkflowGitOperations = GitWorkflowGitOperations()

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-checkpoint-prune")
    gitCommand("init", "--initial-branch", "main")
    gitCommand("config", "user.email", "runtime@skill-bill.test")
    gitCommand("config", "user.name", "Skill Bill Runtime")
    gitCommand("config", "commit.gpgsign", "false")
    write("owned/Base.kt", "base\n")
    gitCommand("add", "-A")
    gitCommand("commit", "-m", "base")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `prune with blank manifest commit_sha leaves every checkpoint ref intact`() {
    val issueKey = "SKILL-190"
    val subtaskId = "1"
    seedRefs(issueKey, subtaskId, count = 2)

    val result =
      git.pruneSubtaskCheckpointRefs(
        repoRoot = repo,
        request =
          FeatureTaskRuntimeCheckpointRefPruneRequest(
            issueKey = issueKey,
            subtaskId = subtaskId,
            manifestCommitSha = null,
          ),
        record = {},
      )

    assertFalse(result.attempted)
    assertEquals(2, listedRefCount(issueKey, subtaskId))
  }

  @Test
  fun `prune run twice succeeds and leaves no checkpoint ref for the subtask`() {
    val issueKey = "SKILL-190"
    val subtaskId = "2"
    seedRefs(issueKey, subtaskId, count = 3)
    val request =
      FeatureTaskRuntimeCheckpointRefPruneRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        manifestCommitSha = head(),
      )

    val first = git.pruneSubtaskCheckpointRefs(repo, request, record = {})
    val second = git.pruneSubtaskCheckpointRefs(repo, request, record = {})

    assertTrue(first.attempted)
    assertTrue(second.attempted)
    assertEquals(3, first.deletedRefCount)
    assertEquals(0, second.deletedRefCount)
    assertEquals(0, listedRefCount(issueKey, subtaskId))
  }

  @Test
  fun `interrupted prune resumes and completes without manual intervention`() {
    val issueKey = "SKILL-190"
    val subtaskId = "3"
    seedRefs(issueKey, subtaskId, count = 3)
    val refs =
      (0 until 3).map { sequence ->
        featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequence)
      }
    assertTrue(
      git.deleteCheckpointRef(repo, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, refs[0]) is
        WorkflowGitOperationResult.Ok,
    )
    val request =
      FeatureTaskRuntimeCheckpointRefPruneRequest(
        issueKey = issueKey,
        subtaskId = subtaskId,
        manifestCommitSha = head(),
      )

    val resumed = git.pruneSubtaskCheckpointRefs(repo, request, record = {})

    assertTrue(resumed.attempted)
    assertEquals(2, resumed.deletedRefCount)
    assertEquals(0, listedRefCount(issueKey, subtaskId))
  }

  @Test
  fun `blocked subtask retention leaves checkpoint refs when manifest row is not complete`() {
    val issueKey = "SKILL-190"
    seedRefs(issueKey, "5", count = 2)
    val manifest =
      DecompositionManifest(
        issueKey = issueKey,
        featureName = "one-commit-per-subtask",
        parentSpecPath = ".feature-specs/$issueKey/spec.md",
        baseBranch = "main",
        featureBranch = "feat/skill-190",
        status = "blocked",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 5, action = "blocked"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 5,
              name = "blocked subtask",
              specPath = ".feature-specs/$issueKey/spec_subtask_5.md",
              status = "blocked",
              commitSha = null,
              blockedReason = "needs repair",
            ),
          ),
      )

    pruneEligibleCheckpointRefsForManifest(manifest, git, repo, record = {})

    assertEquals(2, listedRefCount(issueKey, "5"))
  }

  @Test
  fun `two consecutive reset prunes do not grow the checkpoint namespace`() {
    val issueKey = "SKILL-190"
    seedRefs(issueKey, "1", count = 2)
    seedRefs(issueKey, "2", count = 2)

    pruneResetSubtaskCheckpointRefs(git, repo, issueKey, listOf(1, 2), record = {})
    seedRefs(issueKey, "1", count = 2)
    seedRefs(issueKey, "2", count = 2)
    pruneResetSubtaskCheckpointRefs(git, repo, issueKey, listOf(1, 2), record = {})

    assertEquals(0, listedRefCount(issueKey, "1"))
    assertEquals(0, listedRefCount(issueKey, "2"))
  }

  @Test
  fun `prune with recorded sha but no remote reachability leaves checkpoint refs intact`() {
    val issueKey = "SKILL-190"
    val subtaskId = "6"
    seedRefs(issueKey, subtaskId, count = 2)
    val localOnlySha = head()

    val result =
      git.pruneSubtaskCheckpointRefs(
        repoRoot = repo,
        request =
          FeatureTaskRuntimeCheckpointRefPruneRequest(
            issueKey = issueKey,
            subtaskId = subtaskId,
            manifestCommitSha = localOnlySha,
            featureBranch = "feat/skill-190",
          ),
        record = {},
      )

    assertFalse(result.attempted)
    assertEquals(2, listedRefCount(issueKey, subtaskId))
  }

  @Test
  fun `prune with superseded recorded sha on published branch deletes checkpoint refs`() {
    val issueKey = "SKILL-201"
    val subtaskId = "2"
    val base = head()
    gitCommand("checkout", "-B", "feat/skill-201", base)
    write("owned/Subtask2.kt", "v1\n")
    gitCommand("add", "-A")
    gitCommand("commit", "-m", "subtask 2 v1")
    val supersededSha = head()
    seedRefs(issueKey, subtaskId, count = 2)
    gitCommand("checkout", "-B", "feat/skill-201", base)
    write("owned/Subtask2.kt", "v2\n")
    gitCommand("add", "-A")
    gitCommand("commit", "-m", "subtask 2 v2")
    gitCommand("remote", "add", "origin", repo.toUri().toString())
    gitCommand("push", "-u", "origin", "feat/skill-201")

    val result =
      git.pruneSubtaskCheckpointRefs(
        repoRoot = repo,
        request =
          FeatureTaskRuntimeCheckpointRefPruneRequest(
            issueKey = issueKey,
            subtaskId = subtaskId,
            manifestCommitSha = supersededSha,
            featureBranch = "feat/skill-201",
          ),
        record = {},
      )

    assertTrue(result.attempted)
    assertEquals(2, result.deletedRefCount)
    assertEquals(0, listedRefCount(issueKey, subtaskId))
  }

  @Test
  fun `reset-driven prune bypasses the manifest commit_sha gate`() {
    val issueKey = "SKILL-190"
    val subtaskId = "4"
    seedRefs(issueKey, subtaskId, count = 1)

    val result =
      git.pruneSubtaskCheckpointRefs(
        repoRoot = repo,
        request =
          FeatureTaskRuntimeCheckpointRefPruneRequest(
            issueKey = issueKey,
            subtaskId = subtaskId,
            manifestCommitSha = null,
            bypassEligibilityGate = true,
          ),
        record = {},
      )

    assertTrue(result.attempted)
    assertEquals(1, result.deletedRefCount)
    assertEquals(0, listedRefCount(issueKey, subtaskId))
  }

  private fun seedRefs(
    issueKey: String,
    subtaskId: String,
    count: Int,
  ) {
    val sha = head()
    repeat(count) { sequence ->
      val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequence)
      assertTrue(
        git.updateCheckpointRef(repo, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, ref, sha) is
          WorkflowGitOperationResult.Ok,
      )
    }
  }

  private fun listedRefCount(
    issueKey: String,
    subtaskId: String,
  ): Int =
    when (
      val listed =
        git.listCheckpointRefs(repo, featureTaskRuntimeSubtaskCheckpointRefPrefix(issueKey, subtaskId))
    ) {
      is WorkflowGitNameListResult.Listed -> listed.names.size
      is WorkflowGitNameListResult.Failed -> error("checkpoint ref listing failed: ${listed.error}")
    }

  private fun head(): String = gitCommand("rev-parse", "HEAD")

  private fun write(
    relative: String,
    content: String,
  ) {
    val target = repo.resolve(relative)
    target.parent?.createDirectories()
    target.writeText(content)
  }

  private fun gitCommand(vararg args: String): String {
    val process =
      ProcessBuilder(listOf("git", "-C", repo.toString()) + args.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }
}
