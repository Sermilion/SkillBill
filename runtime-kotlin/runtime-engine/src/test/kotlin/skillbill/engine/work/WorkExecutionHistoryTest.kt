package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.idestatus.model.StandaloneMonitorTarget
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.persistence.UnitOfWork
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkExecutionHistoryTest {
  @Test
  fun `historical truncated reason recovers its full settlement without changing the stored invocation`() {
    val reason = "Full reason. ".repeat(40) + "The required action is here."
    val summary = reason.take(237) + "..."
    val settlements = InMemoryFeatureTaskPhaseSettlementRepository()
    settlements.upsert(
      FeatureTaskPhaseSettlement(
        "workflow",
        "plan",
        3,
        FeatureTaskPhaseSettlementKind.Block,
        JsonCodec.mapToJsonString(
          mapOf(
            SharedPayloadKeys.SUMMARY to summary,
            SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to reason),
          ),
        ),
        Instant.EPOCH.toString(),
      ),
    )
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val unit =
      object : UnitOfWork by database.unitOfWork() {
        override val featureTaskPhaseSettlements = settlements
      }
    val stored = "Phase output reported status 'blocked'. $summary"
    val record =
      StandalonePhaseStatusRecord(
        repositoryIdentity = "repo",
        branchCorrelation = "main",
        issueKey = "SKILL-422",
        workflowId = "workflow",
        invocationId = "invocation",
        phaseId = "plan",
        executionId = "execution",
        statusStoreId = "store",
        runSequence = "1",
        statusRevision = "1",
        lifecycleState = "blocked",
        currentStep = "plan",
        currentActivity = null,
        startedAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        finishedAt = null,
        activeDurationMs = null,
        activeDurationAsOf = null,
        leaseOwner = "owner",
        leaseGeneration = 1,
        leaseExpiresAt = Instant.EPOCH,
        terminalResult = stored,
      )
    assertEquals("Phase output reported status 'blocked'. $reason", resolveExecutionReason(record, unit).text)
    assertEquals(stored, record.terminalResult)
    assertEquals(0, database.writeCalls)
  }

  @Test
  fun `monitors of different pull requests sharing issue and branch keep separate latest executions`() {
    val seven = monitorRecord(7, runSequence = "1")
    val eight = monitorRecord(8, runSequence = "2")
    val sevenAgain = monitorRecord(7, runSequence = "3")

    val latest = latestPhaseExecutions(listOf(seven, eight, sevenAgain))

    assertEquals(setOf("invocation-7-3", "invocation-8-2"), latest.map { it.invocationId }.toSet())
  }

  @Test
  fun `monitor summary names the exact pull request worktree and retained cleanup`() {
    val database = TrackingDatabase(work = emptyList(), workflows = IdeStatusWorkflowStates())
    val record =
      monitorRecord(7, runSequence = "1").copy(
        terminalResult = "Monitor blocked.",
        monitorCleanup =
          StandaloneMonitorCleanup.Retained(MonitorRetentionReason.DIRTY_WORKTREE, "Dirty. Path: /wt/7"),
      )

    val text = requireNotNull(resolveExecutionReason(record, database.unitOfWork()).text)

    assertEquals(
      "Monitor blocked. [Pull request https://github.com/acme/repo/pull/7; repository github.com/acme/repo; " +
        "branch feat/SKILL-427-shared (github.com/fork-7/repo); invocation invocation-7-1; worktree /wt/7; " +
        "cleanup retained (dirty_worktree): Dirty. Path: /wt/7]",
      text,
    )
  }

  private fun monitorRecord(
    number: Int,
    runSequence: String,
  ) = StandalonePhaseStatusRecord(
    repositoryIdentity = "repo",
    branchCorrelation = "feat/SKILL-427-shared",
    issueKey = "SKILL-427",
    workflowId = null,
    invocationId = "invocation-$number-$runSequence",
    phaseId = "monitor",
    executionId = "execution-$number-$runSequence",
    statusStoreId = "store",
    runSequence = runSequence,
    statusRevision = "1",
    lifecycleState = "terminal",
    currentStep = "monitor",
    currentActivity = null,
    startedAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    finishedAt = Instant.EPOCH,
    activeDurationMs = null,
    activeDurationAsOf = null,
    leaseOwner = "owner",
    leaseGeneration = 1,
    leaseExpiresAt = Instant.EPOCH,
    terminalResult = null,
    monitorTarget =
      StandaloneMonitorTarget(
        pullRequestKey = "github.com/acme/repo#$number",
        pullRequestUrl = "https://github.com/acme/repo/pull/$number",
        headRepository = "github.com/fork-$number/repo",
        headRef = "feat/SKILL-427-shared",
        callerRoot = "/caller",
        worktreePath = "/wt/$number",
      ),
  )
}
