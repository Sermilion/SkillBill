package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
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
}
