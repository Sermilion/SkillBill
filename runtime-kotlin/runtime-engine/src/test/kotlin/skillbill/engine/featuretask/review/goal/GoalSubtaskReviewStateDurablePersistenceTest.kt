package skillbill.engine.featuretask.review.goal

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.reconcileRemediationBaseCoherence
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskGitIntegrationDatabase
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskGitIntegrationWorkflowRepository
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputBlocked
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputReady
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassCarryForward
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassInFlight
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.runloop.observability.paused
import skillbill.engine.featuretask.runner.disposition
import skillbill.engine.goalrunner.status.completed
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewPassResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.label()
private val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label()
private val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label()
private val FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.label()
private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()

class GoalSubtaskReviewStateDurablePersistenceTest {
  private val workflowId = "wftr-skill142-1"

  @Test
  fun `the resolved tier, deciding rule, and remediation base sha all survive a reload`() {
    val recorder = recorderWith(pausedState())

    recorder.updateReviewState(workflowId) {
      it.copy(
        resolvedTier = CodeReviewExecutionMode.INLINE,
        decidingRule = "auto_mode_by_pass_number:pass_n_inline",
      )
    }
    recorder.updateReviewState(workflowId) { it.copy(remediationBaseSha = "b".repeat(40)) }

    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals(CodeReviewExecutionMode.INLINE, reloaded.resolvedTier)
    assertEquals("auto_mode_by_pass_number:pass_n_inline", reloaded.decidingRule)
    assertEquals("b".repeat(40), reloaded.remediationBaseSha)
  }

  @Test
  fun `resume reuses the baseline and consumed pass count exactly`() {
    val recorder = recorderWith(pausedState())

    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals("a".repeat(40), reloaded.reviewBaseSha, "review_base_sha is immutable across the pause.")
    assertEquals(listOf("scratch/untracked.txt"), reloaded.baselineUntrackedPaths)
    assertEquals(1, reloaded.completedPassCount)
    assertNull(reloaded.reservedPassNumber, "A consumed pass must never be re-reserved by a plain resume.")
  }

  private fun recorderWith(
    state: GoalSubtaskReviewState,
    repository: FeatureTaskGitIntegrationWorkflowRepository = FeatureTaskGitIntegrationWorkflowRepository(),
    goalBranch: String = "feat/SKILL-142",
    checkpointIdentities: List<FeatureTaskRuntimeCheckpointIdentity> = emptyList(),
  ): FeatureTaskRuntimeGoalContinuationRecorder {
    val engine = WorkflowEngine()
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val artifactsPatch =
      linkedMapOf<String, Any?>(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
          FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = "SKILL-142",
            subtaskId = 5,
            suppressPr = true,
            goalBranch = goalBranch,
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to state.toPersistenceWire(),
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY to
          state.passResults.associate { result ->
            result.passNumber.toString() to """{"phase_id":"review","status":"completed"}"""
          },
      )
    if (checkpointIdentities.isNotEmpty()) {
      artifactsPatch[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY] =
        checkpointIdentities.asCheckpointIdentitiesArtifactEntry()
    }
    val seeded =
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "review",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifactsPatch),
          sessionId = "fis-001",
        ),
      ).toRecord()
    repository.saveFeatureTaskWorkflow(seeded, FeatureTaskWorkflowMode.RUNTIME)
    return FeatureTaskRuntimeGoalContinuationRecorder(
      FeatureTaskGitIntegrationDatabase(repository),
      NoopRuntimeDiagnostics,
      Clock.systemUTC(),
    )
  }

  private data class Skill15GitFixture(
    val repoRoot: Path,
    val parent: String,
    val orphanedBase: String,
  )

  private data class CommittedUnrecordedFixture(
    val repoRoot: Path,
    val parent: String,
    val checkpointSha: String,
  )

  private data class SkipRecordedDescendantFixture(
    val repoRoot: Path,
    val parent: String,
    val checkpointSha: String,
    val skipRecordedTip: String,
  )

  private data class UnreachableGitFixture(
    val repoRoot: Path,
    val unreachable: String,
  )

  private fun skill15GitFixture(): Skill15GitFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-skill15")
    val remoteRoot = Files.createTempDirectory("skillbill-durable-skill15-remote")
    git(remoteRoot, "init", "--bare")
    git(repoRoot, "init", "-b", "main")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "remote", "add", "origin", remoteRoot.toString())
    Files.writeString(repoRoot.resolve("tracked.txt"), "root\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "root")
    git(repoRoot, "push", "-u", "origin", "main")
    git(repoRoot, "checkout", "-b", "feat/skill-15")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "commit", "-am", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-a\n")
    git(repoRoot, "commit", "-am", "sibling-a")
    val orphanedBase = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "reset", "--hard", parent)
    Files.writeString(repoRoot.resolve("tracked.txt"), "sibling-b\n")
    git(repoRoot, "commit", "-am", "sibling-b")
    return Skill15GitFixture(repoRoot, parent, orphanedBase)
  }

  private fun committedUnrecordedFixture(): CommittedUnrecordedFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-unrecorded")
    git(repoRoot, "init", "-b", "feat/skill-15")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "checkpoint\n")
    git(repoRoot, "commit", "-am", "chore(SKILL-176): remediation checkpoint")
    val checkpointSha = git(repoRoot, "rev-parse", "HEAD")
    return CommittedUnrecordedFixture(repoRoot, parent, checkpointSha)
  }

  private fun skipRecordedDescendantFixture(): SkipRecordedDescendantFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-skip-descendant")
    git(repoRoot, "init", "-b", "feat/skill-15")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "parent\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "parent")
    val parent = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "checkpoint\n")
    git(repoRoot, "commit", "-am", "chore(SKILL-176): remediation checkpoint")
    val checkpointSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("tracked.txt"), "skip-tip\n")
    git(repoRoot, "commit", "-am", "skip-recorded tip ahead of identity")
    val skipRecordedTip = git(repoRoot, "rev-parse", "HEAD")
    return SkipRecordedDescendantFixture(repoRoot, parent, checkpointSha, skipRecordedTip)
  }

  private fun unreachableOnlyGitFixture(): UnreachableGitFixture {
    val repoRoot = Files.createTempDirectory("skillbill-durable-unreachable")
    git(repoRoot, "init", "-b", "feat/orphan-goal")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("tracked.txt"), "goal\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "goal tip")
    git(repoRoot, "checkout", "--orphan", "unrelated-root")
    val prior = git(repoRoot, "ls-files").lines().filter { it.isNotBlank() }
    if (prior.isNotEmpty()) {
      git(repoRoot, *(listOf("rm", "-f", "--") + prior).toTypedArray())
    }
    Files.writeString(repoRoot.resolve("other.txt"), "unrelated\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "unrelated root")
    val unreachable = git(repoRoot, "rev-parse", "HEAD")
    git(repoRoot, "checkout", "feat/orphan-goal")
    git(repoRoot, "branch", "-D", "unrelated-root")
    return UnreachableGitFixture(repoRoot, unreachable)
  }

  private fun git(
    repoRoot: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun gitOpsWithoutBaselineRecovery(): WorkflowGitOperations =
    object : WorkflowGitOperations by realGitOps() {
      override fun captureGoalSubtaskReviewBaseline(
        repoRoot: Path,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult =
        GoalSubtaskReviewBaselineResult(status = WorkflowGitOperationStatus.ERROR, error = "unsupported")

      override fun buildGoalSubtaskReviewInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult =
        GoalSubtaskReviewInputResult(status = WorkflowGitOperationStatus.ERROR, error = "unsupported")

      override fun recoverGoalSubtaskReviewBaseline(
        repoRoot: Path,
        request: GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult =
        GoalSubtaskReviewBaselineResult(
          status = WorkflowGitOperationStatus.ERROR,
          error = "Goal-subtask review baseline recovery is not supported by this git adapter.",
        )
    }

  private fun realGitOps(): WorkflowGitOperations = GitWorkflowGitOperations()

  private fun pausedState(): GoalSubtaskReviewState {
    val initial =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = listOf("scratch/untracked.txt"),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    val passOne =
      initial.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings =
          listOf(
            GoalSubtaskReviewCompactFinding(
              severity = "blocker",
              label = "GoalRunnerPolicy",
              text = "a Blocker the remediation pass must disposition",
              findingId = "F-001",
            ),
          ),
      )
    return passOne.copy(
      disposition = GoalSubtaskReviewDisposition.PAUSED,
      blockerDispositions =
        listOf(
          GoalSubtaskBlockerDisposition(
            findingId = "F-001",
            verdict = GoalSubtaskBlockerDispositionVerdict.UNRESOLVED,
            evidence = listOf("still reproduces at the same seam"),
          ),
        ),
    )
  }

  @Test
  fun `a crash mid-remediation resumes the same reserved pass without allocating another`() {
    val reserved =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = listOf("scratch/untracked.txt"),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass()
    val recorder = recorderWith(reserved)

    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals(1, reloaded.reservedPassNumber, "Resume must reuse the pass the crashed attempt reserved.")
    assertEquals(0, reloaded.completedPassCount)

    val reReserved = recorder.reserveGoalReviewPass(workflowId)
    val inFlight = assertIs<GoalSubtaskReviewPassInFlight>(reReserved)
    assertEquals(1, inFlight.state.reservedPassNumber, "A resumed reservation is reused, never re-allocated.")
  }

  @Test
  fun `a completed pass carries forward instead of reserving pass one again`() {
    val completed =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    val recorder = recorderWith(completed)

    val reservation = recorder.reserveGoalReviewPass(workflowId)

    assertIs<GoalSubtaskReviewPassCarryForward>(reservation)
    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals(1, reloaded.completedPassCount)
    assertEquals(1, reloaded.passResults.size)
    assertNull(reloaded.reservedPassNumber)
  }

  @Test
  fun `unreachable remediation base recovers and persists the ancestor with evidence`() {
    val fixture = skill15GitFixture()
    val orphaned = fixture.orphanedBase
    val parent = fixture.parent
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = orphaned)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")

    val prepared =
      recorder.buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      )

    assertIs<GoalSubtaskReviewInputReady>(prepared, prepared.toString())
    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals(parent, reloaded.remediationBaseSha, "durable remediation_base_sha must repoint to the ancestor")
    assertEquals("a".repeat(40), reloaded.reviewBaseSha, "immutable review base must stay untouched")
    val artifacts = repository.taskRuntimeArtifacts(workflowId)
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(artifacts[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY]),
      )
    val entry = evidence.single()
    assertEquals(orphaned, entry["original_sha"])
    assertEquals(parent, entry["replacement_sha"])
    assertEquals("remediation_base_sha", entry["repointed_field"])
    assertEquals("base_not_ancestor", entry["failure_reason"])
    assertContains(entry["failure_message"].toString(), orphaned)
    assertEquals("feat/skill-15", entry["goal_branch"])
  }

  @Test
  fun `recovery gate allows completed passes and reserved remediation while refusing terminal dispositions`() {
    val fixture = skill15GitFixture()
    val remediationState =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.orphanedBase)
    assertIs<GoalSubtaskReviewInputReady>(
      recorderWith(remediationState, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      ),
    )

    val zeroPassState =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = fixture.orphanedBase,
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass()
    assertIs<GoalSubtaskReviewInputReady>(
      recorderWith(zeroPassState, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      ),
    )

    val paused = remediationState.copy(disposition = GoalSubtaskReviewDisposition.PAUSED)
    assertIs<GoalSubtaskReviewInputBlocked>(
      recorderWith(paused, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      ),
    )

    val capped =
      deepRemediationState(completedPasses = 1).copy(
        disposition = GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED,
        remediationBaseSha = fixture.orphanedBase,
        passResults =
          listOf(
            GoalSubtaskReviewPassResult(
              passNumber = 1,
              verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
              reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.1",
              unresolvedFindingCount = 1,
              findings = listOf(GoalSubtaskReviewCompactFinding("major", "Service", "Missing behavior")),
              executedMode = CodeReviewExecutionMode.INLINE,
            ),
          ),
      )
    assertIs<GoalSubtaskReviewInputBlocked>(
      recorderWith(capped, goalBranch = "feat/skill-15").buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      ),
    )
  }

  @Test
  fun `reachable bases take no recovery path and keep byte-identical review input`() {
    val fixture = skill15GitFixture()
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.parent)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")
    val git = realGitOps()

    val first =
      assertIs<GoalSubtaskReviewInputReady>(
        recorder.buildGoalReviewInput(workflowId, git, fixture.repoRoot),
      )
    val second =
      assertIs<GoalSubtaskReviewInputReady>(
        recorder.buildGoalReviewInput(workflowId, git, fixture.repoRoot),
      )

    assertEquals(first.input.reviewText, second.input.reviewText)
    assertEquals(first.input.deltaDigest, second.input.deltaDigest)
    assertNull(
      repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
      "reachable resume must write no recovery evidence",
    )
    assertEquals(fixture.parent, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
  }

  @Test
  fun `resume coherence recovers an orphaned remediation base to the nearest reachable ancestor`() {
    val fixture = skill15GitFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    assertTrue(head != fixture.orphanedBase, "fixture must leave the orphan unreachable from HEAD")
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.orphanedBase)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")
    val gitOps = realGitOps()

    val coherent =
      assertIs<RemediationBaseCoherent>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOps, fixture.repoRoot),
      )

    assertEquals(fixture.parent, coherent.state?.remediationBaseSha)
    val persisted = recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha
    assertEquals(fixture.parent, persisted)
    assertNotEquals(head, persisted)
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
        ),
      )
    assertEquals("base_not_ancestor", evidence.first()["failure_reason"])
    assertEquals(fixture.orphanedBase, evidence.first()["original_sha"])
    assertEquals(fixture.parent, evidence.first()["replacement_sha"])
  }

  @Test
  fun `resume coherence blocks when an orphaned base has no recoverable ancestor`() {
    val fixture = skill15GitFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.orphanedBase)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")

    val blocked =
      assertIs<RemediationBaseBlocked>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(
          workflowId,
          gitOpsWithoutBaselineRecovery(),
          fixture.repoRoot,
        ),
      )

    assertContains(blocked.operatorGuidance, "feat/skill-15")
    assertEquals(fixture.orphanedBase, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    assertNotEquals(head, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
        ),
      )
    assertEquals("reconciliation_blocked", evidence.single()["failure_reason"])
  }

  @Test
  fun `resume coherence heals committed-but-unrecorded remediation checkpoint to the identity sha`() {
    val fixture = committedUnrecordedFixture()
    val state =
      deepRemediationState(completedPasses = 1)
        .reserveNextPass()
        .copy(remediationBaseSha = fixture.parent)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val identity =
      FeatureTaskRuntimeCheckpointIdentity(
        sequenceNumber = 0,
        issueKey = "SKILL-176",
        subtaskId = "15",
        checkpointRef = "refs/skill-bill/checkpoints/SKILL-176/15/0",
        branch = "feat/skill-15",
        phaseId = "review",
        generation = 1,
        ownedPathDigest = "a".repeat(64),
        ownedPathCount = 1,
        commitSha = fixture.checkpointSha,
        recordedAt = "2026-08-10T00:00:00Z",
        loopId = "review_fix",
        parentSha = fixture.parent,
      )
    val recorder =
      recorderWith(
        state,
        repository,
        goalBranch = "feat/skill-15",
        checkpointIdentities = listOf(identity),
      )
    val gitOps = realGitOps()
    git(fixture.repoRoot, "update-ref", identity.checkpointRef, fixture.checkpointSha)

    val healed =
      assertIs<RemediationBaseCoherent>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, gitOps, fixture.repoRoot),
      )

    assertEquals(fixture.checkpointSha, healed.state?.remediationBaseSha)
    assertEquals(fixture.checkpointSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
        ),
      )
    assertEquals("committed_but_unrecorded", evidence.single()["failure_reason"])
  }

  @Test
  fun `resume coherence is a no-op when the recorded base already agrees with HEAD`() {
    val fixture = skill15GitFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = head)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, repository, goalBranch = "feat/skill-15")

    recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot)

    assertEquals(head, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    assertNull(repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY])
  }

  @Test
  fun `resume coherence keeps a Skip-recorded descendant tip ahead of the review_fix identity`() {
    val fixture = skipRecordedDescendantFixture()
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.skipRecordedTip)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val identity =
      FeatureTaskRuntimeCheckpointIdentity(
        sequenceNumber = 0,
        issueKey = "SKILL-176",
        subtaskId = "15",
        checkpointRef = "refs/skill-bill/checkpoints/SKILL-176/15/0",
        branch = "feat/skill-15",
        phaseId = "review",
        generation = 1,
        ownedPathDigest = "a".repeat(64),
        ownedPathCount = 1,
        commitSha = fixture.checkpointSha,
        recordedAt = "2026-08-10T00:00:00Z",
        loopId = "review_fix",
        parentSha = fixture.parent,
      )
    val recorder =
      recorderWith(
        state,
        repository,
        goalBranch = "feat/skill-15",
        checkpointIdentities = listOf(identity),
      )

    val after =
      assertIs<RemediationBaseCoherent>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot),
      )

    assertEquals(fixture.skipRecordedTip, after.state?.remediationBaseSha)
    assertEquals(fixture.skipRecordedTip, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    assertEquals(fixture.skipRecordedTip, git(fixture.repoRoot, "rev-parse", "HEAD"))
    assertNull(
      repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
      "coherent Skip-recorded descendant must not emit recovery evidence",
    )
  }

  @Test
  fun `upserting a repair receipt for the same round replaces in place across a reload`() {
    val first =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = "b".repeat(40),
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
              findingId = "F-001",
              noEditReason = "already present on the tree",
            ),
          ),
      )
    val replacement =
      first.copy(
        entries =
          listOf(
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
              findingId = "F-001",
            ),
          ),
      )
    val recorder = recorderWith(pausedState())
    recorder.updateReviewState(workflowId) { it.upsertRepairReceipt(first) }
    recorder.updateReviewState(workflowId) { it.upsertRepairReceipt(replacement) }
    val reloaded = assertNotNull(recorder.reviewStateRecorder.reviewState(workflowId))
    assertEquals(1, reloaded.repairReceipts.size)
    assertEquals(
      FeatureTaskRuntimeRepairOutcome.ADDRESSED,
      reloaded.repairReceipts.single().entries.single().outcome,
    )
  }

  @Test
  fun `recovery that cannot find a reachable base blocks naming the sha and branch`() {
    val fixture = unreachableOnlyGitFixture()
    val state =
      deepRemediationState(completedPasses = 1)
        .copy(remediationBaseSha = fixture.unreachable)
    val prepared =
      recorderWith(state, goalBranch = "feat/orphan-goal").buildGoalReviewInput(
        workflowId,
        realGitOps(),
        fixture.repoRoot,
      )
    val blocked = assertIs<GoalSubtaskReviewInputBlocked>(prepared)
    assertContains(blocked.reason, fixture.unreachable)
    assertContains(blocked.reason, "feat/orphan-goal")
  }

  private fun deepRemediationState(completedPasses: Int): GoalSubtaskReviewState {
    require(completedPasses in 0..1) { "collapsed topology allows at most one completed review pass" }
    var state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = listOf("scratch/untracked.txt"),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    repeat(completedPasses) {
      state =
        state.reserveNextPass().completeReservedPass(
          verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
          unresolvedFindingCount = 1,
          findings = emptyList(),
        )
    }
    return state
  }
}
