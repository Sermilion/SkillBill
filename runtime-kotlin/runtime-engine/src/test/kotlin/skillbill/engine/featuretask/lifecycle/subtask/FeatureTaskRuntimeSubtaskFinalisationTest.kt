package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMetadata
import skillbill.engine.featuretask.lifecycle.continuation.commitShaFromPhasePayload
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoff
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalised
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val GIT_TIMEOUT_SECONDS = 60L

class FeatureTaskRuntimeSubtaskFinalisationTest {
  private val issueKey = "SKILL-190"
  private val subtaskId = "5"
  private val branch = "feat/skill-190-finalisation"
  private val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, subtaskId)
  private val agentSubject = "SKILL-190: runtime-owned subtask finalisation"

  @Test
  fun `a checkpointed subtask ends with one amended commit carrying the agent subject and trailer`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val result = finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt"))

    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(result)
    assertEquals(commitsBefore, commitCount(repo.root), "the amend must not add a second subtask commit")
    assertEquals(git(repo.root, "rev-parse", "HEAD"), finalised.commitSha)
    assertEquals(agentSubject, git(repo.root, "log", "-1", "--format=%s"))
    assertEquals(
      "$issueKey/$subtaskId",
      git(repo.root, "log", "-1", "--format=%(trailers:key=Skill-Bill-Subtask,valueonly)").trim(),
    )
    assertContains(git(repo.root, "log", "-1", "--format=%b"), "phase=commit_push")
    assertEquals("final\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `commit_push sweeps foreign prior-run checkpoint refs and still finalises`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("stale.txt"), "abandoned prior run\n")
    git(repo.root, "add", "stale.txt")
    git(repo.root, "commit", "-m", "abandoned prior checkpoint")
    val staleSha = git(repo.root, "rev-parse", "HEAD")
    val staleRef0 = identity.checkpointRefName(0)
    val staleRef1 = identity.checkpointRefName(1)
    git(repo.root, "update-ref", staleRef0, staleSha)
    git(repo.root, "update-ref", staleRef1, staleSha)
    git(repo.root, "reset", "--hard", "HEAD~1")

    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    records.clear()

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt")),
      )

    assertTrue(records.any { it.contains("foreign occupant") && it.contains("swept") })
    assertEquals(
      checkpointSha,
      git(repo.root, "rev-parse", staleRef0),
      "sequence 0 must preserve the pre-amend commit after reclaim",
    )
    assertEquals(
      staleRef0,
      git(
        repo.root,
        "for-each-ref",
        "--format=%(refname)",
        "refs/skill-bill/checkpoints/$issueKey/$subtaskId/",
      ),
      "prior-run sequence refs must be gone after the sweep",
    )
    assertEquals(finalised.commitSha, remoteBranchTip(repo.remote))
  }

  @Test
  fun `a subtask that never checkpointed ends with one created commit carrying the enumerated work`() {
    val repo = repoWithRemote()
    val baseSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "only-work\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
      )

    assertEquals(baseSha, git(repo.root, "rev-parse", "HEAD~1"))
    assertEquals(agentSubject, git(repo.root, "log", "-1", "--format=%s"))
    assertEquals("only-work\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `commit_push includes feature-spec paths in the subtask commit`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec\n")
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec edited by the run\n")
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = null, paths = listOf("owned.txt", ".feature-specs/$issueKey/spec.md")),
      )

    val committed =
      git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(listOf(".feature-specs/$issueKey/spec.md", "owned.txt"), committed)
    assertEquals(committed, finalised.stagedPaths.sorted())
    assertEquals("", git(repo.root, "diff", "--name-only"), "no deliverable dirt may remain")
    assertEquals("", git(repo.root, "diff", "--cached", "--name-only"))
    assertTrue(records.none { it.contains("governed feature specs") })
  }

  @Test
  fun `commit_push includes dirty paths outside the remembered inventory`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "owned\n")
    Files.writeString(repo.root.resolve("appeared.txt"), "also this subtask\n")
    val result =
      finalise(
        repo,
        durableCommitSha = null,
        paths = listOf("owned.txt"),
      )
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(result)
    val committed =
      git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(listOf("appeared.txt", "owned.txt"), committed)
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `commit_push commits nested agent history missing from the ownership inventory`() {
    val repo = repoWithRemote()
    val history = repo.root.resolve("runtime-kotlin/runtime-engine/agent/history.md")
    Files.createDirectories(history.parent)
    Files.writeString(history, "engine history\n")
    Files.writeString(repo.root.resolve("owned.txt"), "owned\n")
    val result =
      finalise(
        repo,
        durableCommitSha = null,
        paths = listOf("owned.txt"),
      )
    val finalised = assertIs<FeatureTaskRuntimeSubtaskFinalised>(result)
    val committed =
      git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(
      listOf("owned.txt", "runtime-kotlin/runtime-engine/agent/history.md"),
      committed,
    )
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `a foreign HEAD commit is never amended`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("human.txt"), "hand written\n")
    git(repo.root, "add", "human.txt")
    git(repo.root, "commit", "-m", "human: unrelated fix")
    val humanSha = git(repo.root, "rev-parse", "HEAD")
    val humanTree = git(repo.root, "cat-file", "-p", humanSha)
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
      )

    assertEquals(humanSha, git(repo.root, "rev-parse", "HEAD~1"))
    assertEquals(humanTree, git(repo.root, "cat-file", "-p", humanSha), "the human commit must be byte-identical")
    assertEquals("hand written\n", git(repo.root, "show", "$humanSha:human.txt") + "\n")
    assertTrue(finalised.commitSha != humanSha)
  }

  @Test
  fun `the normal path never force-pushes over a remote that moved`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "work\n")
    val other = tempRoot("skillbill-finalisation-other")
    git(other, "clone", repo.remote.toString(), other.resolve("clone").toString())
    val clone = other.resolve("clone")
    configureIdentity(clone)
    git(clone, "checkout", "-b", branch)
    Files.writeString(clone.resolve("remote-only.txt"), "someone else\n")
    git(clone, "add", "remote-only.txt")
    git(clone, "commit", "-m", "remote moved")
    git(clone, "push", "-u", "origin", branch)
    val remoteTip = git(repo.remote, "rev-parse", branch)

    val blocked =
      assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
        finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
      )

    assertContains(blocked.reason, "could not be pushed")
    assertEquals(remoteTip, git(repo.remote, "rev-parse", branch), "a rejected normal push must not move the remote")
  }

  @Test
  fun `a reopened published subtask force-pushes under a matching lease and overwrites a stale remote after fetch`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "published\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    git(repo.root, "push", "-u", "origin", branch)
    val publishedSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "reopened\n")
    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = publishedSha, paths = listOf("owned.txt")),
      )

    assertTrue(finalised.forcedWithLease)
    assertEquals(commitsBefore, commitCount(repo.root), "a reopened published subtask still ends with one commit")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
    assertTrue(records.any { it.contains("git push --force-with-lease") })
    assertTrue(records.any { it.contains("git fetch origin +refs/heads/$branch") })

    val staleRepo = staleLease(repo)
    records.clear()
    Files.writeString(staleRepo.root.resolve("owned.txt"), "reopened again\n")
    val remoteTipBefore = git(staleRepo.remote, "rev-parse", branch)
    val overwritten =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(
          staleRepo,
          durableCommitSha = git(staleRepo.root, "rev-parse", "HEAD"),
          paths = listOf("owned.txt"),
          sequenceNumber = 1,
        ),
      )

    assertTrue(overwritten.forcedWithLease)
    assertEquals(overwritten.commitSha, git(staleRepo.remote, "rev-parse", branch))
    assertTrue(overwritten.commitSha != remoteTipBefore)
    assertTrue(
      !git(staleRepo.remote, "ls-tree", "--name-only", "-r", branch).contains("remote-only.txt"),
      "a refreshed lease must overwrite the remote-only commit instead of blocking",
    )
    assertTrue(records.any { it.contains("git fetch origin +refs/heads/$branch") })
  }

  @Test
  fun `the durable pointer is recorded before the push and a failed recording blocks without pushing`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val remoteTipsSeenWhileRecording = mutableListOf<String>()
    atRecordTime = { remoteTipsSeenWhileRecording += remoteBranchTip(repo.remote) }

    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt")),
      )

    assertEquals(listOf(finalised.commitSha), recordedCommits)
    assertEquals(listOf(""), remoteTipsSeenWhileRecording, "the pointer must be durable before the push")

    val second = repoWithRemote()
    Files.writeString(second.root.resolve("owned.txt"), "work\n")
    recordedCommits.clear()
    atRecordTime = {}
    recordFailure = "needs_human: the workflow row was absent"

    val blocked =
      assertIs<FeatureTaskRuntimeSubtaskFinalisationBlocked>(
        finalise(second, durableCommitSha = null, paths = listOf("owned.txt")),
      )

    assertEquals("needs_human: the workflow row was absent", blocked.reason)
    assertEquals(recordedCommits.single(), git(second.root, "rev-parse", "HEAD"), "the commit must stand")
    assertEquals("", remoteBranchTip(second.remote), "an unrecorded commit must not be published")
  }

  @Test
  fun `a reopened subtask that checkpointed over its published commit still leases its push`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "published\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    git(repo.root, "push", "-u", "origin", branch)
    val publishedSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "reopened checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "--amend", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    val commitsBefore = commitCount(repo.root)

    Files.writeString(repo.root.resolve("owned.txt"), "reopened final\n")
    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt"), sequenceNumber = 1),
      )

    assertTrue(finalised.forcedWithLease, "the remote still carries the pre-checkpoint commit")
    assertEquals(commitsBefore, commitCount(repo.root), "the subtask must still end with one commit")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
    assertTrue(finalised.commitSha != publishedSha)
  }

  @Test
  fun `finalisation amends HEAD when durable checkpoint is an ancestor after branch advanced`() {
    val repo = repoWithRemote()
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    git(repo.root, "checkout", "-b", "merge-side")
    Files.writeString(repo.root.resolve("merge-side.txt"), "side\n")
    git(repo.root, "add", "merge-side.txt")
    git(repo.root, "commit", "-m", "integration side commit")
    git(repo.root, "checkout", branch)
    git(repo.root, "merge", "merge-side", "--no-edit")
    assertTrue(git(repo.root, "rev-parse", "HEAD") != checkpointSha)
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec only\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf(".feature-specs/$issueKey/spec.md")),
      )

    assertEquals(listOf(".feature-specs/$issueKey/spec.md"), finalised.stagedPaths)
    assertEquals(agentSubject, git(repo.root, "log", "-1", "--format=%s"))
    assertEquals("checkpoint\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals("spec only\n", git(repo.root, "show", "HEAD:.feature-specs/$issueKey/spec.md") + "\n")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `a finalisation with only feature-spec dirt commits that dirt`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec baseline\n")
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec only\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf(".feature-specs/$issueKey/spec.md")),
      )

    assertEquals(listOf(".feature-specs/$issueKey/spec.md"), finalised.stagedPaths)
    assertEquals("checkpoint\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals("spec only\n", git(repo.root, "show", "HEAD:.feature-specs/$issueKey/spec.md") + "\n")
    assertEquals("", git(repo.root, "diff", "--name-only"), "no deliverable dirt may remain")
    assertEquals("", git(repo.root, "diff", "--cached", "--name-only"), "nothing may be left staged")
    assertEquals(finalised.commitSha, git(repo.remote, "rev-parse", branch))
  }

  @Test
  fun `an already-finalised HEAD commits leftover feature-spec dirt`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(
      repo.root.resolve(".feature-specs/$issueKey/decomposition-manifest.yaml"),
      "status: pending\n",
    )
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val published =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt")),
      )

    Files.writeString(
      repo.root.resolve(".feature-specs/$issueKey/decomposition-manifest.yaml"),
      "status: blocked\n",
    )
    records.clear()
    val pushed =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(
          repo,
          durableCommitSha = published.commitSha,
          paths = listOf(".feature-specs/$issueKey/decomposition-manifest.yaml"),
          sequenceNumber = 1,
        ),
      )

    assertEquals(pushed.commitSha, git(repo.remote, "rev-parse", branch))
    assertEquals("final\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertEquals(
      "status: blocked\n",
      git(repo.root, "show", "HEAD:.feature-specs/$issueKey/decomposition-manifest.yaml") + "\n",
    )
    assertEquals("", git(repo.root, "diff", "--name-only"), "no deliverable dirt may remain")
  }

  @Test
  fun `a published subtask whose remote branch was deleted is pushed as a new remote branch`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(
      repo.root.resolve(".feature-specs/$issueKey/decomposition-manifest.yaml"),
      "status: pending\n",
    )
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve("owned.txt"), "checkpoint\n")
    git(repo.root, "add", "owned.txt")
    git(repo.root, "commit", "-m", "$issueKey: subtask $subtaskId\n\nprovisional\n\n${identity.trailer}")
    val checkpointSha = git(repo.root, "rev-parse", "HEAD")
    Files.writeString(repo.root.resolve("owned.txt"), "final\n")
    val published =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = checkpointSha, paths = listOf("owned.txt")),
      )

    git(repo.remote, "update-ref", "-d", "refs/heads/$branch")
    assertEquals(published.commitSha, git(repo.root, "rev-parse", "origin/$branch"))
    assertEquals("", remoteBranchTip(repo.remote))
    Files.writeString(repo.root.resolve("owned.txt"), "recreated\n")
    records.clear()
    val recreated =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(
          repo,
          durableCommitSha = published.commitSha,
          paths = listOf("owned.txt"),
          sequenceNumber = 1,
        ),
      )

    assertEquals(recreated.commitSha, git(repo.remote, "rev-parse", branch))
    assertEquals("recreated\n", git(repo.root, "show", "HEAD:owned.txt") + "\n")
    assertTrue(
      records.any { it.contains("the remote no longer has") },
      records.joinToString("\n"),
    )
  }

  @Test
  fun `a partial changed_paths list still commits every dirty implementation path`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve(".feature-specs/$issueKey"))
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec\n")
    git(repo.root, "add", ".feature-specs")
    git(repo.root, "commit", "-m", "operator committed the spec")
    Files.writeString(repo.root.resolve(".feature-specs/$issueKey/spec.md"), "spec dirt ok\n")
    Files.writeString(repo.root.resolve("owned.txt"), "enumerated\n")
    Files.writeString(repo.root.resolve("leftover.txt"), "validate repair\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = null, paths = listOf("owned.txt")),
      )

    val committed =
      git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(
      listOf(".feature-specs/$issueKey/spec.md", "leftover.txt", "owned.txt"),
      committed,
    )
    assertEquals("", git(repo.root, "diff", "--name-only"), "no deliverable dirt may remain")
    assertEquals(finalised.commitSha, remoteBranchTip(repo.remote))
  }

  @Test
  fun `an untracked nested directory is committed file-by-file rather than left behind`() {
    val repo = repoWithRemote()
    Files.createDirectories(repo.root.resolve("owned/nested"))
    Files.writeString(repo.root.resolve("owned/nested/One.kt"), "one\n")
    Files.writeString(repo.root.resolve("owned/nested/Two.kt"), "two\n")

    val finalised =
      assertIs<FeatureTaskRuntimeSubtaskFinalised>(
        finalise(repo, durableCommitSha = null, paths = emptyList()),
      )

    val committed =
      git(repo.root, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD")
        .lines().filter { it.isNotBlank() }.sorted()
    assertEquals(listOf("owned/nested/One.kt", "owned/nested/Two.kt"), committed)
    assertEquals(finalised.commitSha, remoteBranchTip(repo.remote))
    assertEquals("", git(repo.root, "status", "--porcelain"), "no deliverable dirt may remain")
  }

  @Test
  fun `a blank outcome message is rejected before any git write`() {
    val blank =
      FeatureTaskRuntimeSubtaskFinalisation.readHandoff(
        envelope(message = "   ", paths = listOf("owned.txt")),
      )
    val absentPaths =
      FeatureTaskRuntimeSubtaskFinalisation.readHandoff(
        mapOf(
          "produced_outputs" to
            mapOf(
              "commit_push_result" to mapOf("message" to agentSubject),
            ),
        ),
      )

    assertContains(
      assertIs<FeatureTaskRuntimeCommitPushHandoffInvalid>(blank).reason,
      "`commit_push_result.message` is missing or blank",
    )
    assertIs<FeatureTaskRuntimeCommitPushHandoffValid>(absentPaths)
  }

  @Test
  fun `the captured sha is the value every commit_sha reader resolves`() {
    val withSha =
      FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
        envelope(message = agentSubject, paths = listOf("owned.txt")),
        commitSha = "b".repeat(40),
      )

    assertEquals("b".repeat(40), withSha.commitShaFromPhasePayload())
  }

  private val records = mutableListOf<String>()
  private val recordedCommits = mutableListOf<String>()
  private var recordFailure: String? = null
  private var atRecordTime: (String) -> Unit = {}

  private data class Fixture(val root: Path, val remote: Path)

  private fun finalise(
    fixture: Fixture,
    durableCommitSha: String?,
    paths: List<String>,
    sequenceNumber: Int = 0,
  ): FeatureTaskRuntimeSubtaskFinalisationResult =
    FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = realGitOps(),
      repoRoot = fixture.root,
      record = { records += it },
      recordCommit = { sha, _ ->
        recordedCommits += sha
        atRecordTime(sha)
        recordFailure
      },
    ).finalise(
      FeatureTaskRuntimeSubtaskFinaliseRequest(
        identity = identity,
        durableCommitSha = durableCommitSha,
        sequenceNumber = sequenceNumber,
        handoff = FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = agentSubject, changedPaths = paths),
        metadata =
          FeatureTaskRuntimeCheckpointMetadata(
            phaseId = "commit_push",
            loopId = null,
            generation = 0,
            branch = branch,
            intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
          ),
      ),
    )

  private fun staleLease(fixture: Fixture): Fixture {
    val clone = tempRoot("skillbill-finalisation-stale").resolve("clone")
    git(clone.parent, "clone", fixture.remote.toString(), clone.toString())
    configureIdentity(clone)
    git(clone, "checkout", branch)
    Files.writeString(clone.resolve("remote-only.txt"), "someone else\n")
    git(clone, "add", "remote-only.txt")
    git(clone, "commit", "-m", "remote moved after the lease was taken")
    git(clone, "push", "origin", branch)
    return fixture
  }

  private fun repoWithRemote(): Fixture {
    val base = tempRoot("skillbill-finalisation")
    val remote = base.resolve("remote.git")
    git(base, "init", "--bare", "--initial-branch=main", remote.toString())
    val root = base.resolve("work")
    Files.createDirectories(root)
    git(root, "init", "--initial-branch=main")
    configureIdentity(root)
    git(root, "remote", "add", "origin", remote.toString())
    Files.writeString(root.resolve("README.md"), "base\n")
    git(root, "add", "README.md")
    git(root, "commit", "-m", "base")
    git(root, "push", "-u", "origin", "main")
    git(root, "checkout", "-b", branch)
    return Fixture(root = root, remote = remote)
  }

  private fun configureIdentity(repoRoot: Path) {
    val hooks = Files.createDirectories(repoRoot.resolve(".git/skillbill-empty-hooks"))
    git(repoRoot, "config", "user.email", "runtime@skill-bill.test")
    git(repoRoot, "config", "user.name", "Skill Bill Runtime")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "config", "tag.gpgsign", "false")
    git(repoRoot, "config", "core.hooksPath", hooks.toString())
    git(repoRoot, "config", "push.default", "simple")
  }

  private val tempRoots = mutableListOf<Path>()

  private fun tempRoot(prefix: String): Path = Files.createTempDirectory(prefix).also(tempRoots::add)

  @AfterTest
  fun deleteFixtureRepositories() {
    tempRoots.reversed().forEach { root ->
      root.toFile().walkBottomUp().forEach { it.delete() }
    }
    tempRoots.clear()
  }

  private fun commitCount(repoRoot: Path): Int = git(repoRoot, "rev-list", "--count", "HEAD").toInt()

  private fun remoteBranchTip(remote: Path): String =
    git(remote, "for-each-ref", "--format=%(objectname)", "refs/heads/$branch")

  private fun git(
    repoRoot: Path,
    vararg args: String,
  ): String {
    val builder =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList())
        .redirectErrorStream(true)
    builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
    builder.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText().trim()

    if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
      error("git ${args.joinToString(" ")} did not finish within ${GIT_TIMEOUT_SECONDS}s: $output")
    }
    val exitCode = process.exitValue()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  internal fun envelope(
    message: String,
    paths: List<String>,
  ): Map<String, Any?> =
    mapOf(
      "produced_outputs" to
        mapOf(
          "commit_push_result" to mapOf("message" to message, "changed_paths" to paths),
        ),
    )

  private fun realGitOps(): WorkflowGitOperations = GitWorkflowGitOperations()
}
