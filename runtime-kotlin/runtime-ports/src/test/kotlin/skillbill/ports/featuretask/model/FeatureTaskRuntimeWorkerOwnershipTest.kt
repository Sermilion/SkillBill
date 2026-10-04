package skillbill.ports.featuretask.model

import skillbill.error.core.SkillBillRuntimeException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeWorkerOwnershipTest {
  @Test
  fun `malformed lease timestamp fails with the ownership schema error`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = "workflow",
        generation = 1,
        ownerToken = "owner-token-123456",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 1,
        processBirthToken = "birth",
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2026-01-01T00:00:00Z",
        expiresAt = "malformed",
        phaseId = "implement",
        phaseAttempt = 1,
      )
    }
  }
}
