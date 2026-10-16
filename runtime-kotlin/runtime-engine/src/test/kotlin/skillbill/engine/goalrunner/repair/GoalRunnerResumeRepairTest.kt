package skillbill.engine.goalrunner.repair

import skillbill.application.InMemoryWorkflowStates
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.model.GoalRunnerResumeStatus
import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RESUME_ISSUE_KEY = "SKILL-176"
private val UNREACHABLE_SHA = "a".repeat(40)
private val RECOVERED_SHA = "b".repeat(40)
private val REVIEWED_SHA = "c".repeat(40)

internal class GoalRunnerResumeRepairTest : GoalRunnerRepairFixtures() {
  @Test
  fun `resume clears a stale lease, a runner_interrupted pause and an unreachable remediation base`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-resume-stale"
    saveChild(workflows, workflowId, remediatedReview(UNREACHABLE_SHA))
    val manifestStore = MutableRepairManifestStore(workflowId, interruptedControlState(expiredLease()))
    val service = resumeService(workflows, manifestStore)

    val result = service.resume(RESUME_ISSUE_KEY, Path.of("."))

    assertEquals(GoalRunnerResumeStatus.RESUMED, result.status)
    assertEquals(GOAL_PAUSE_REASON_RUNNER_INTERRUPTED, result.clearedPauseReason)
    assertEquals(
      setOf(
        GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
        GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
        GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE,
      ),
      result.appliedRepairs.map { it.wedgeClass }.toSet(),
    )
    assertEquals(null, manifestStore.controlStateValue.executionLease)
    assertEquals(false, manifestStore.controlStateValue.paused)
    val review =
      requireNotNull(GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(childArtifacts(workflows, workflowId)))
    assertEquals(RECOVERED_SHA, review.remediationBaseSha)
    assertEquals(1, review.completedPassCount)
  }

  @Test
  fun `resume leaves the lease of a live runner alone`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-resume-live"
    saveChild(workflows, workflowId, healthyReviewState())
    val liveLease =
      expiredLease().copy(
        heartbeatAt = Instant.parse("2999-01-01T00:00:00Z"),
        expiresAt = Instant.parse("2999-01-01T00:00:30Z"),
      )
    val manifestStore = MutableRepairManifestStore(workflowId, interruptedControlState(liveLease))
    val service = resumeService(workflows, manifestStore, LiveProcessSupervisor)

    val result = service.resume(RESUME_ISSUE_KEY, Path.of("."))

    assertTrue(result.appliedRepairs.isEmpty())
    assertEquals(liveLease, manifestStore.controlStateValue.executionLease)
  }

  @Test
  fun `resume stops on an operator-required wedge without clearing anything`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-resume-incompatible"
    saveChild(
      workflows,
      workflowId,
      healthyReviewState(),
      mapOf("goal_planning_import" to mapOf("phase_output_contract_version" to "0.3")),
    )
    val stale = interruptedControlState(expiredLease())
    val manifestStore = MutableRepairManifestStore(workflowId, stale)
    val service = resumeService(workflows, manifestStore)

    val result = service.resume(RESUME_ISSUE_KEY, Path.of("."))

    assertEquals(GoalRunnerResumeStatus.OPERATOR_REQUIRED, result.status)
    assertContains(result.refusalReason.orEmpty(), "Keep the workflow and checkpoints intact")
    assertTrue(result.appliedRepairs.isEmpty())
    assertEquals(stale, manifestStore.controlStateValue)
  }

  @Test
  fun `resume of a healthy paused goal writes no repair`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-resume-healthy"
    saveChild(workflows, workflowId, healthyReviewState())
    val before = childArtifacts(workflows, workflowId)
    val manifestStore =
      MutableRepairManifestStore(
        workflowId,
        GoalRunnerControlState(
          paused = true,
          pauseRequested = true,
          pauseConsumed = true,
          pauseReason = "operator_request",
          pausedAt = "2000-01-01T00:00:00Z",
        ),
      )
    val service = resumeService(workflows, manifestStore)

    val result = service.resume(RESUME_ISSUE_KEY, Path.of("."))

    assertEquals(GoalRunnerResumeStatus.RESUMED, result.status)
    assertTrue(result.appliedRepairs.isEmpty())
    assertEquals(before, childArtifacts(workflows, workflowId))
  }

  private fun saveChild(
    workflows: InMemoryWorkflowStates,
    workflowId: String,
    review: GoalSubtaskReviewState,
    extraArtifacts: Map<String, Any?> = emptyMap(),
  ) {
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = review,
          extraArtifacts = extraArtifacts,
        ),
      ),
      RUNTIME,
    )
  }

  private fun childArtifacts(
    workflows: InMemoryWorkflowStates,
    workflowId: String,
  ): Map<String, Any?> =
    decodeWorkflowArtifactsForTest(
      requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
    )

  private fun remediatedReview(remediationBaseSha: String): GoalSubtaskReviewState =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = REVIEWED_SHA,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.APPROVED,
      unresolvedFindingCount = 0,
      findings = emptyList(),
    ).copy(remediationBaseSha = remediationBaseSha)

  private fun interruptedControlState(lease: GoalRunnerExecutionLease): GoalRunnerControlState =
    GoalRunnerControlState(
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
      pausedAt = "2000-01-01T00:00:00Z",
      executionLease = lease,
    )

  private fun expiredLease(): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "parent-owner",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 42,
      processBirthToken = "birth",
      heartbeatAt = "2000-01-01T00:00:00Z",
      expiresAt = "2000-01-01T00:00:30Z",
    )

  private fun resumeService(
    workflows: InMemoryWorkflowStates,
    manifestStore: MutableRepairManifestStore,
    workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  ): GoalRunnerStatusService {
    val store =
      repairStore(workflows, git = ReachableGit(unreachableShas = setOf(UNREACHABLE_SHA), recoveredSha = RECOVERED_SHA))
    return testGoalRunnerStatusService(
      manifestStore = manifestStore,
      outcomeStore = store,
      ports = GoalRunnerStatusTestPorts(workerSupervisor = workerSupervisor, childRepairStore = store),
    )
  }
}
