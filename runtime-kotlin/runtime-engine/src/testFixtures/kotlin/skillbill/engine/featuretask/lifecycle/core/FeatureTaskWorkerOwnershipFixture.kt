package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership

fun ownership(
  expiresAt: String = "2999-01-01T00:00:30Z",
  ownerToken: String = "old-owner-token-0001",
  generation: Long = 1,
) = FeatureTaskRuntimeWorkerOwnership(
  workflowId = WORKFLOW_ID,
  generation = generation,
  ownerToken = ownerToken,
  hostIdentity = "host",
  bootIdentity = "boot",
  pid = 100,
  processBirthToken = "birth-100",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2026-07-14T10:00:00Z",
  expiresAt = expiresAt,
  phaseId = "implement",
  phaseAttempt = 1,
)
