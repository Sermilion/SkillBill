package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.FINALISED_COMMIT_PUSH_OUTPUT
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.GoalSubtaskReviewDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val RUNNER_BRIEFING_ISSUE_KEY = RUNNER_TEST_ISSUE_KEY

private fun briefingFixtureRecordedOutputs() =
  listOf(
    FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
    FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
    FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
    FeatureTaskRuntimePhaseOutput("simplify", 1, SIMPLIFY_OUTPUT),
    FeatureTaskRuntimePhaseOutput("audit", 1, VALID_AUDIT_OUTPUT),
    FeatureTaskRuntimePhaseOutput("review", 1, VALID_REVIEW_OUTPUT),
    FeatureTaskRuntimePhaseOutput("verify_findings", 1, VALID_VERIFY_FINDINGS_OUTPUT),
    FeatureTaskRuntimePhaseOutput("validate", 1, validJsonOutput("validate")),
    FeatureTaskRuntimePhaseOutput("write_history", 1, validJsonOutput("write_history")),
    FeatureTaskRuntimePhaseOutput("commit_push", 1, FINALISED_COMMIT_PUSH_OUTPUT),
  )

private fun briefingFixtureInvariants() =
  FeatureTaskRuntimeRunInvariants(
    specReference = RUNNER_TEST_SPEC_REFERENCE,
    featureSize = FeatureTaskRuntimeFeatureSize.SMALL,
    acceptanceCriteria = listOf("AC-1", "AC-2"),
    mandatesAndOverrides = listOf("mandate-X"),
  )

private fun briefingsForCompletedPhases(
  invariants: FeatureTaskRuntimeRunInvariants,
  recorded: List<FeatureTaskRuntimePhaseOutput>,
) = COMPLETED_PHASES_CLEAN_RUN.associateWith { phaseId ->
  val declaration = FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(phaseId, invariants.featureSize)
  val handoff =
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration = declaration,
        runInvariants = invariants,
        recordedOutputs = recorded,
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1"),
      ),
    )
  FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
}

private fun assertBriefingRunInvariants(briefings: Map<String, FeatureTaskRuntimePhaseLaunchBriefing>) {
  briefings.forEach { (phaseId, briefing) ->
    assertEquals(RUNNER_TEST_SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
    assertEquals("SMALL", briefing.featureSize, "feature size for $phaseId")
    assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
    assertContains(briefing.briefingText, "feature_size: SMALL")
    assertContains(briefing.briefingText, RUNNER_TEST_SPEC_REFERENCE)
    assertContains(briefing.briefingText, "mandate-X", message = "mandates missing for $phaseId")
  }
}

internal fun assertEachPhaseBriefingIncludesRunInvariantsUpstreamAndReviewDiff() {
  val invariants = briefingFixtureInvariants()
  val briefings = briefingsForCompletedPhases(invariants, briefingFixtureRecordedOutputs())
  assertBriefingRunInvariants(briefings)
  assertContains(briefings.getValue("plan").briefingText, "Fixture preplan prose for downstream plan.")
  assertContains(
    briefings.getValue("implement").briefingText,
    "Fixture plan prose for downstream implement and audit.",
  )
  assertEquals(listOf("current_unit_of_work"), briefings.getValue("review").derivedContextKeys)
  assertContains(briefings.getValue("review").briefingText, "current_unit_of_work")
  assertPrKeepsSelfReadBranchDiffOnBriefing(briefings.getValue("pr"))
}

internal fun assertPrKeepsSelfReadBranchDiffOnBriefing(briefing: FeatureTaskRuntimePhaseLaunchBriefing) {
  assertEquals(
    listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF),
    briefing.derivedContextKeys,
  )
  assertContains(briefing.briefingText, "pr_branch_diff")
  assertContains(briefing.briefingText, "read the branch diff yourself")
}

private fun inlineGoalContinuationHarness(
  repoRoot: Path,
  git: RecordingWorkflowGitOperations,
  commitPushOutput: String,
): RunnerHarness {
  val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(commitPushOutput))
  harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
  check(
    harness.goalContinuationRecorder.recordGoalContinuationState(
      GoalContinuationStateRecordRequest(
        workflowId = WORKFLOW_ID,
        continuation =
          FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = RUNNER_BRIEFING_ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
        reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      ),
    ),
  )
  return harness
}

private fun seedPreReviewPhases(harness: RunnerHarness) {
  harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
  harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
  harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
  harness.seedPhase("simplify", "completed", 1, phaseAgent("simplify"), SIMPLIFY_OUTPUT)
  harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
}

private fun pausedReviewState(reviewedDeltaDigest: String) =
  GoalSubtaskReviewState.initial(
    reviewBaseSha = "0".repeat(40),
    baselineUntrackedPaths = emptyList(),
    codeReviewMode = CodeReviewExecutionMode.INLINE,
  ).reserveNextPass().completeReservedPass(
    verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
    unresolvedFindingCount = 1,
    findings =
      listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "StaleCap",
          text = "unresolved",
          findingId = "F-001",
        ),
      ),
  ).copy(
    disposition = GoalSubtaskReviewDisposition.PAUSED,
    reviewedDeltaDigest = reviewedDeltaDigest,
    remediationBaseSha = "9".repeat(40),
  )

private fun seedStaleReviewHarness(
  tempPrefix: String,
  trackedDelta: String,
): Pair<RunnerHarness, RecordingWorkflowGitOperations> {
  val repoRoot = Files.createTempDirectory(tempPrefix)
  val git =
    RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
  git.goalReviewBuildResults +=
    GoalSubtaskReviewInputResult(
      status = WorkflowGitOperationStatus.ERROR,
      error = "Persisted review base '${"9".repeat(40)}' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
  git.goalReviewBuildResults +=
    GoalSubtaskReviewInputResult(
      status = WorkflowGitOperationStatus.OK,
      input =
        GoalSubtaskReviewInput(
          reviewBaseSha = "0".repeat(40),
          currentHeadSha = COMMITTED_HEAD_SHA,
          trackedDelta = trackedDelta,
          ownedUntrackedPatches = "",
        ),
    )
  val harness = inlineGoalContinuationHarness(repoRoot, git, validJsonOutput("commit_push"))
  harness.seedReviewPhase("completed", 1, validJsonOutput("review"), reviewPassNumber = 1)
  return harness to git
}

internal fun assertNonScopeReviewPrepFailureSurfacesEvidenceStoreCause() {
  val evidenceStoreCause = "[evidence-store] retaining producer-output evidence for review:0:2 failed"
  val fixedScopeSentence =
    "Goal-subtask review preparation could not establish the exact durable review scope."
  val repoRoot = Files.createTempDirectory("skillbill-runtime-review-prep-nonscope")
  val git =
    RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
  val harness = inlineGoalContinuationHarness(repoRoot, git, validJsonOutput("commit_push"))
  seedPreReviewPhases(harness)
  harness.repository.failSaveWhen = { row ->
    val artifacts =
      JsonCodec.parseObjectOrNull(row.artifactsJson)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        .orEmpty()
    val reserved = (artifacts["goal_subtask_review_state"] as? Map<*, *>)?.get("reserved_pass_number")
    if (reserved != null) {
      throw IllegalStateException(evidenceStoreCause)
    }
    false
  }
  val blocked =
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(
        harness.request().copy(
          transitionsOverride =
            FeatureTaskRuntimeTransitionDeclaration(
              forwardPhaseIds = listOf("preplan", "plan", "implement", "simplify", "audit", "review"),
              backwardEdges = emptyList(),
            ),
        ),
      ),
    )
  assertEquals("review", blocked.lastIncompletePhase)
  assertContains(blocked.blockedReason, evidenceStoreCause)
  assertContains(blocked.blockedReason, "Goal-subtask review reservation failed")
  assertTrue(
    fixedScopeSentence !in blocked.blockedReason,
    "fixed scope sentence must not replace the injected non-scope cause",
  )
  val phaseBlocked =
    harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseBlocked>()
      .single { it.phaseId == "review" }
  assertContains(phaseBlocked.blockedReason, evidenceStoreCause)
  assertTrue(fixedScopeSentence !in phaseBlocked.blockedReason)
  val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
  assertContains(requireNotNull(reviewRecord.blockedReason), evidenceStoreCause)
}

internal fun assertCappedReviewStaleIgnoresUnreachableRemediationBase() {
  val (harness, git) =
    seedStaleReviewHarness(
      "skillbill-runtime-stale-unreachable-remediation",
      "immutable-delta\n",
    )
  val paused =
    pausedReviewState(
      GoalSubtaskReviewInput(
        reviewBaseSha = "0".repeat(40),
        currentHeadSha = COMMITTED_HEAD_SHA,
        trackedDelta = "immutable-delta\n",
        ownedUntrackedPatches = "",
      ).deltaDigest,
    )
  checkNotNull(harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { paused })
  harness.seedRawReviewResults(paused)
  val generationBefore =
    harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[
      "feature_task_runtime_review_generation",
    ]
  harness.runner.run(
    harness.request().copy(
      transitionsOverride =
        FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
    ),
  )
  val after = requireNotNull(harness.goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID))
  assertEquals(GoalSubtaskReviewDisposition.PAUSED, after.disposition)
  assertEquals("9".repeat(40), after.remediationBaseSha, "staleness must not heal the remediation base")
  assertEquals(0, git.goalReviewRecoverCalls, "recovery belongs to review preparation, not staleness")
  assertEquals(
    generationBefore,
    harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["feature_task_runtime_review_generation"],
  )
}

internal fun assertCappedReviewStaleReopensWhenImmutableDigestChanged() {
  val (harness, git) =
    seedStaleReviewHarness(
      "skillbill-runtime-stale-changed-immutable",
      "new-delta\n",
    )
  val paused =
    pausedReviewState(
      GoalSubtaskReviewInput(
        reviewBaseSha = "0".repeat(40),
        currentHeadSha = COMMITTED_HEAD_SHA,
        trackedDelta = "old-delta\n",
        ownedUntrackedPatches = "",
      ).deltaDigest,
    )
  checkNotNull(harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { paused })
  harness.seedRawReviewResults(paused)
  harness.runner.run(
    harness.request().copy(
      transitionsOverride =
        FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
    ),
  )
  val after = requireNotNull(harness.goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID))
  assertEquals(GoalSubtaskReviewDisposition.PENDING, after.disposition)
  assertNull(after.remediationBaseSha, "invalidation resets review state; recovery is not the staleness path")
  assertEquals(0, git.goalReviewRecoverCalls)
}
