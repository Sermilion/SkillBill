package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.contracts.JsonCodec
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.reconcileRemediationBaseCoherence
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskGitIntegrationDatabase
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskGitIntegrationWorkflowRepository
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStepWireUpdate
import skillbill.engine.featuretask.model.subtask.RemediationBaseBlocked
import skillbill.engine.featuretask.model.subtask.RemediationBaseCoherent
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

class RemediationBaseReconciliationUnderAmendTest {
  private val workflowId = "wftr-skill190-reconcile"
  private val issueKey = "SKILL-190"
  private val subtaskId = "4"
  private val goalBranch = "feat/skill-190"

  @Test
  fun `orphaned review_fix identity commit still resolves through its checkpoint ref`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val identity =
      reviewFixIdentity(
        sequenceNumber = 1,
        commitSha = fixture.postRemediationSha,
        parentSha = preFixSha,
      )
    seedPreFixRef(fixture.repoRoot, identity, preFixSha)
    val state = remediationState(remediationBaseSha = preFixSha)
    val recorder = recorderWith(state, listOf(identity))
    val git = realGitOps()

    val result = recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, git, fixture.repoRoot)
    val coherent = assertIs<RemediationBaseCoherent>(result)
    assertEquals(preFixSha, coherent.state?.remediationBaseSha)
    val ancestry = git.isCommitAncestor(fixture.repoRoot, preFixSha, fixture.postRemediationSha)
    assertFalse(ancestry is WorkflowGitOperationResult.Ok && ancestry.value == "true")
  }

  @Test
  fun `unresolvable checkpoint ref recovers the nearest reachable base instead of blocking`() {
    val fixture = amendRemediationFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(fixture.repoRoot, "update-ref", "-d", ref)
    val identity =
      reviewFixIdentity(
        sequenceNumber = 1,
        commitSha = fixture.postRemediationSha,
        parentSha = fixture.preRemediationSha,
      )
    val state = remediationState(remediationBaseSha = fixture.preRemediationSha)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, listOf(identity), repository)

    val coherent =
      assertIs<RemediationBaseCoherent>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot),
      )
    val mergeBase = git(fixture.repoRoot, "merge-base", fixture.preRemediationSha, head)
    assertEquals(mergeBase, coherent.state?.remediationBaseSha)
    val persisted = recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha
    assertEquals(mergeBase, persisted)
    assertNotEquals(head, persisted)
    assertNotEquals(fixture.preRemediationSha, persisted)
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
        ),
      )
    val entry = evidence.first()
    assertEquals("FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence", entry["seam"])
    assertEquals(fixture.preRemediationSha, entry["original_sha"])
    assertEquals(mergeBase, entry["replacement_sha"])
    assertEquals("base_not_ancestor", entry["failure_reason"])
  }

  @Test
  fun `blocks with operator guidance when no reachable base can be recovered`() {
    val fixture = amendRemediationFixture()
    val head = git(fixture.repoRoot, "rev-parse", "HEAD")
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(fixture.repoRoot, "update-ref", "-d", ref)
    val identity =
      reviewFixIdentity(
        sequenceNumber = 1,
        commitSha = fixture.postRemediationSha,
        parentSha = fixture.preRemediationSha,
      )
    val state = remediationState(remediationBaseSha = fixture.preRemediationSha)
    val repository = FeatureTaskGitIntegrationWorkflowRepository()
    val recorder = recorderWith(state, listOf(identity), repository)

    val blocked =
      assertIs<RemediationBaseBlocked>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(
          workflowId,
          gitOpsWithoutBaselineRecovery(),
          fixture.repoRoot,
        ),
      )
    assertContains(blocked.operatorGuidance, workflowId)
    assertContains(blocked.operatorGuidance, goalBranch)
    assertContains(blocked.operatorGuidance, ref)
    assertContains(blocked.operatorGuidance, "skill-bill goal repair $issueKey --subtask")
    assertFalse(blocked.operatorGuidance.contains("--issue-key"))
    assertEquals(fixture.preRemediationSha, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    assertNotEquals(head, recorder.reviewStateRecorder.reviewState(workflowId)?.remediationBaseSha)
    val evidence =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(
          repository.taskRuntimeArtifacts(workflowId)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY],
        ),
      )
    val entry = evidence.single()
    assertEquals("FeatureTaskRuntimeGoalContinuationRecorder.reconcileRemediationBaseCoherence", entry["seam"])
    assertEquals(ref, entry["value_used"])
    assertEquals("resolvable review_fix checkpoint ref commit", entry["value_expected"])
    assertNotNull(entry["cause"])
  }

  @Test
  fun `a stored base that resolves but left the branch is not reported as unresolvable`() {
    val fixture = amendRemediationFixture()
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(fixture.repoRoot, "update-ref", "-d", ref)
    val state = remediationState(remediationBaseSha = fixture.preRemediationSha)
    val recorder = recorderWith(state, emptyList())

    val blocked =
      assertIs<RemediationBaseBlocked>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(
          workflowId,
          gitOpsWithoutBaselineRecovery(),
          fixture.repoRoot,
        ),
      )
    assertFalse(
      blocked.operatorGuidance.contains("also failed to resolve"),
      "a stored base that still resolves must not be reported as unresolvable: ${blocked.operatorGuidance}",
    )
    assertContains(blocked.operatorGuidance, "not reachable from the branch")
  }

  @Test
  fun `two review_fix passes across an amend boundary receive a non-empty diff base`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val identity =
      reviewFixIdentity(
        sequenceNumber = 1,
        commitSha = fixture.postRemediationSha,
        parentSha = preFixSha,
      )
    seedPreFixRef(fixture.repoRoot, identity, preFixSha)
    val state = remediationState(remediationBaseSha = preFixSha)
    val recorder = recorderWith(state, listOf(identity))
    val diff = git(fixture.repoRoot, "diff", preFixSha, fixture.postRemediationSha)
    assertTrue(diff.contains("pass-one-marker"), "diff must contain pass-one remediation changes")
    val coherent =
      assertIs<RemediationBaseCoherent>(
        recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, realGitOps(), fixture.repoRoot),
      )
    assertEquals(preFixSha, coherent.state?.remediationBaseSha)
  }

  @Test
  fun `no remediation checkpoint yet performs no HEAD read`() {
    val repoRoot = Files.createTempDirectory("skillbill-no-remediation-yet")
    initRepo(repoRoot)
    val state = remediationState(remediationBaseSha = null)
    val recorder = recorderWith(state, emptyList())
    val git =
      object : WorkflowGitOperations by realGitOps() {
        override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
          WorkflowGitOperationResult.Failed(error = "HEAD read forbidden on cheap path")
      }
    val result = recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, git, repoRoot)
    assertIs<RemediationBaseCoherent>(result)
  }

  @Test
  fun `rollback after remediation amend restores prior identity commit and is idempotent`() {
    val fixture = amendRemediationFixture()
    val preFixSha = fixture.preRemediationSha
    val ref0 = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 0)
    git(fixture.repoRoot, "update-ref", ref0, fixture.implementSha)
    val identity0 =
      reviewFixIdentity(
        sequenceNumber = 0,
        commitSha = preFixSha,
        parentSha = fixture.parentSha,
        refName = ref0,
      )
    seedPreFixRef(fixture.repoRoot, identity0, fixture.implementSha)
    val identity1 =
      reviewFixIdentity(
        sequenceNumber = 1,
        commitSha = fixture.postRemediationSha,
        parentSha = preFixSha,
      )
    seedPreFixRef(fixture.repoRoot, identity1, preFixSha)
    val rollbackHead = fixture.postRemediationSha
    rollbackRemediation(
      repoRoot = fixture.repoRoot,
      commitSha = rollbackHead,
      parentSha = preFixSha,
      identityRecorded = true,
      identities = listOf(identity0, identity1),
    )
    assertEquals(preFixSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    rollbackRemediation(
      repoRoot = fixture.repoRoot,
      commitSha = rollbackHead,
      parentSha = preFixSha,
      identityRecorded = true,
      identities = listOf(identity0, identity1),
    )
    assertEquals(preFixSha, git(fixture.repoRoot, "rev-parse", "HEAD"))
    val log = git(fixture.repoRoot, "rev-list", "--count", "HEAD")
    assertEquals("2", log.trim())
  }

  @Test
  fun `rollback of first checkpoint removes the subtask commit`() {
    val repoRoot = Files.createTempDirectory("skillbill-first-checkpoint-rollback")
    initRepo(repoRoot)
    val parentSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "subtask\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "-m", "subtask commit")
    val commitSha = git(repoRoot, "rev-parse", "HEAD")
    rollbackRemediation(
      repoRoot = repoRoot,
      commitSha = commitSha,
      parentSha = parentSha,
      identityRecorded = false,
      identities = emptyList(),
    )
    assertEquals(parentSha, git(repoRoot, "rev-parse", "HEAD"))
    assertEquals("1", git(repoRoot, "rev-list", "--count", "HEAD").trim())
  }

  private data class AmendRemediationFixture(
    val repoRoot: Path,
    val parentSha: String,
    val implementSha: String,
    val preRemediationSha: String,
    val postRemediationSha: String,
  )

  private fun amendRemediationFixture(): AmendRemediationFixture {
    val repoRoot = Files.createTempDirectory("skillbill-amend-remediation")
    initRepo(repoRoot)
    val parentSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "implement\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "-m", "subtask implement")
    val implementSha = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("owned.txt"), "pass-one-marker\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "--amend", "-m", "subtask remediation pre-fix")
    val preRemediationSha = git(repoRoot, "rev-parse", "HEAD")
    val ref = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, 1)
    git(repoRoot, "update-ref", ref, preRemediationSha)
    Files.writeString(repoRoot.resolve("owned.txt"), "pass-two-marker\n")
    git(repoRoot, "add", "owned.txt")
    git(repoRoot, "commit", "--amend", "-m", "subtask remediation post-fix")
    val postRemediationSha = git(repoRoot, "rev-parse", "HEAD")
    return AmendRemediationFixture(
      repoRoot = repoRoot,
      parentSha = parentSha,
      implementSha = implementSha,
      preRemediationSha = preRemediationSha,
      postRemediationSha = postRemediationSha,
    )
  }

  private fun remediationState(remediationBaseSha: String?): GoalSubtaskReviewState {
    var state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    state =
      state.reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        unresolvedFindingCount = 1,
        findings = emptyList(),
      )
    return state.reserveNextPass().copy(remediationBaseSha = remediationBaseSha)
  }

  private fun seedPreFixRef(
    repoRoot: Path,
    identity: FeatureTaskRuntimeCheckpointIdentity,
    preFixSha: String,
  ) {
    git(repoRoot, "update-ref", identity.checkpointRef, preFixSha)
  }

  private fun reviewFixIdentity(
    sequenceNumber: Int,
    commitSha: String,
    parentSha: String?,
    refName: String = featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber),
  ): FeatureTaskRuntimeCheckpointIdentity =
    FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = sequenceNumber,
      issueKey = issueKey,
      subtaskId = subtaskId,
      checkpointRef = refName,
      branch = goalBranch,
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      generation = 1,
      ownedPathDigest = "a".repeat(64),
      ownedPathCount = 1,
      commitSha = commitSha,
      recordedAt = "2026-08-19T00:00:00Z",
      loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
      parentSha = parentSha,
    )

  @Test
  fun `legacy checkpoint and terminal recovery refusals retain attempts outputs and finalization evidence`() {
    val cases =
      listOf(WorkflowStatus.RUNNING to true) +
        WorkflowStatus.terminalStatuses.flatMap {
          listOf(it to true, it to false)
        }
    cases.forEach { (status, legacy) ->
      listOf("pending", "running", "completed").forEach { finalizationStatus ->
        val home = Files.createTempDirectory("remediation-refusal")
        try {
          val database = phaseRunDatabase(home, Clock.systemUTC())
          val recorder =
            recorderWith(
              state = remediationState(remediationBaseSha = null),
              checkpointIdentities = listOf(reviewFixIdentity(1, "a".repeat(40), "b".repeat(40))),
              database = database,
              seed =
                ReconciliationWorkflowSeed(
                  status,
                  legacyCheckpointRecord =
                    if (legacy) {
                      assertNotNull(
                        JsonCodec.anyToStringAnyMap(
                          JsonCodec.parseValue(
                            """{"contract_version":"0.1","checkpoints":[{"commit_sha":"retained-legacy-evidence"}]}""",
                          ),
                        ),
                      )
                    } else {
                      null
                    },
                  stepUpdates =
                    listOf(
                      FeatureTaskRuntimePhaseStepWireUpdate("review", "completed", 3),
                      FeatureTaskRuntimePhaseStepWireUpdate("commit_push", finalizationStatus, 2),
                    ),
                ),
            )
          val before = database.read { assertNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
          val git = RecordingWorkflowGitOperations().also { it.headCommitShaValue = "a".repeat(40) }
          val result = recorder.remediationReconciler.reconcileRemediationBaseCoherence(workflowId, git, home)

          assertIs<RemediationBaseBlocked>(result)
          assertFalse(result.operatorGuidance.contains("retained-legacy-evidence"))
          database.read { assertEquals(before, it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
          assertEquals("a".repeat(40), git.headCommitShaValue)
          assertTrue(git.createCommitMessages.isEmpty())
          assertTrue(git.amendCommitMessages.isEmpty())
          assertTrue(git.pushedBranches.isEmpty())
          assertTrue(git.updateCheckpointRefCalls.isEmpty())
          assertTrue(git.resetSoftToCommitCalls.isEmpty())
          assertTrue(git.resetHardToCommitCalls.isEmpty())
          assertEquals(0, git.goalReviewRecoverCalls)
          if (status == WorkflowStatus.RUNNING) {
            assertContains(result.operatorGuidance, "Checkpoint identity semantics are unsupported")
          } else {
            assertContains(result.operatorGuidance, "Terminal workflows")
          }
        } finally {
          home.toFile().deleteRecursively()
        }
      }
    }
  }

  private data class ReconciliationWorkflowSeed(
    val status: WorkflowStatus = WorkflowStatus.RUNNING,
    val legacyCheckpointRecord: Map<String, Any?>? = null,
    val stepUpdates: List<FeatureTaskRuntimePhaseStepWireUpdate>? = null,
  )

  private fun recorderWith(
    state: GoalSubtaskReviewState,
    checkpointIdentities: List<FeatureTaskRuntimeCheckpointIdentity>,
    repository: FeatureTaskGitIntegrationWorkflowRepository = FeatureTaskGitIntegrationWorkflowRepository(),
    database: DatabaseSessionFactory = FeatureTaskGitIntegrationDatabase(repository),
    seed: ReconciliationWorkflowSeed = ReconciliationWorkflowSeed(),
  ): FeatureTaskRuntimeGoalContinuationRecorder {
    val engine = WorkflowEngine()
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    val artifactsPatch =
      linkedMapOf<String, Any?>(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
          FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = issueKey,
            subtaskId = 4,
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
    seed.legacyCheckpointRecord?.let { artifactsPatch[FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY] = it }
    val seeded =
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = seed.status,
          currentStepId = "review",
          stepUpdates =
            WorkflowStepUpdates.from(
              seed.stepUpdates?.map(FeatureTaskRuntimePhaseStepWireUpdate::toWireMap),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(artifactsPatch),
          sessionId = "fis-001",
        ),
      ).toRecord()
    database.transaction { it.workflowStates.saveFeatureTaskWorkflow(seeded, RUNTIME) }
    return FeatureTaskRuntimeGoalContinuationRecorder(
      database,
      NoopRuntimeDiagnostics,
      Clock.systemUTC(),
    )
  }

  private fun rollbackRemediation(
    repoRoot: Path,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
  ) {
    val head = realGitOps().headCommitSha(repoRoot)
    if (head !is WorkflowGitOperationResult.Ok || head.value.trim() != commitSha.trim()) return
    val predecessor =
      when {
        identityRecorded -> {
          val current = identities.lastOrNull { it.commitSha == commitSha }
          if (current == null || current.sequenceNumber == 0) {
            null
          } else {
            identities.find { it.sequenceNumber == current.sequenceNumber - 1 }
          }
        }
        identities.isEmpty() -> null
        else -> identities.maxByOrNull { it.sequenceNumber }
      }
    val restoreSha =
      when {
        predecessor == null -> parentSha?.trim()?.takeIf(String::isNotBlank)
        else -> {
          val predecessorCommitSha = predecessor.commitSha.trim()
          if (predecessorCommitSha.isBlank()) {
            parentSha?.trim()?.takeIf(String::isNotBlank)
          } else {
            val resolved = realGitOps().resolveCommit(repoRoot, predecessorCommitSha)
            resolved.value.orEmpty().trim()
              .takeIf { resolved is WorkflowGitOperationResult.Ok && it.isNotBlank() }
              ?: parentSha?.trim()
          }
        }
      }
    requireNotNull(restoreSha) { "rollback target missing" }
    realGitOps().resetSoftToCommit(repoRoot, restoreSha)
  }

  private fun initRepo(repoRoot: Path) {
    git(repoRoot, "init", "-b", goalBranch)
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    git(repoRoot, "config", "commit.gpgsign", "false")
    Files.writeString(repoRoot.resolve("root.txt"), "root\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "root")
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
}
