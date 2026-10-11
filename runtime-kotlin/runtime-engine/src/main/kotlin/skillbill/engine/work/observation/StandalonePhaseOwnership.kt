package skillbill.engine.work.observation

import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord

internal fun StandalonePhaseStatusRecord.workerOwnership(): FeatureTaskRuntimeWorkerOwnership? =
  processOwner?.let { owner ->
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId ?: invocationId,
      generation = leaseGeneration,
      ownerToken = leaseOwner,
      hostIdentity = owner.hostIdentity,
      bootIdentity = owner.bootIdentity,
      pid = owner.pid,
      processBirthToken = owner.processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = updatedAt.toString(),
      expiresAt = leaseExpiresAt.toString(),
      phaseId = phaseId,
      phaseAttempt = 1,
    )
  }
