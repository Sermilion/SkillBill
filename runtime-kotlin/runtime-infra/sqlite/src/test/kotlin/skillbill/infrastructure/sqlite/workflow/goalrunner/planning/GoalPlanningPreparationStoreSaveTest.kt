package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.workflow.goalrunner.GoalPlanningPreparationStore
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoalPlanningPreparationStoreSaveTest {
  @Test
  fun `saved shared preplan with repair evidence round trips through findSharedPreplan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val payload = "preplan-payload-with-repair"
      val checkpoint =
        sharedCheckpoint().copy(
          payloadSha256 = sha256HexUtf8(payload),
          preplanPayload = payload,
          repairEvidence =
            FeatureTaskRuntimePhaseOutputRepairEvidence(
              format = FeatureTaskRuntimePhaseOutputFormat.YAML,
              originalDigest = "a".repeat(64),
              repairedDigest = "b".repeat(64),
              operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
              sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation("preplan", 0, 1, 2),
            ),
        )
      store.saveSharedPreplan(checkpoint)
      val found = store.findSharedPreplan(identity())
      assertIs<SharedGoalPreplanLookupResult.Found>(found)
      val stored = found.checkpoint
      assertEquals(payload, stored?.preplanPayload)
      assertEquals(checkpoint.repairEvidence?.repairedDigest, stored?.repairEvidence?.repairedDigest)
    }
  }

  @Test
  fun `shared preplan save rejects payload sha mismatch and leaves table empty`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val checkpoint =
        sharedCheckpoint().copy(
          payloadSha256 = "d".repeat(64),
          preplanPayload = "different-payload",
        )
      assertFailsWith<SkillBillRuntimeException> { store.saveSharedPreplan(checkpoint) }
      val found = store.findSharedPreplan(identity())
      assertIs<SharedGoalPreplanLookupResult.Found>(found)
      assertNull(found.checkpoint)
    }
  }
}
