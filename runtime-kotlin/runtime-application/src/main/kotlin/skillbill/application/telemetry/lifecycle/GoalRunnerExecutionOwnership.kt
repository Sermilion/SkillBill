package skillbill.application.telemetry.lifecycle

import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership

fun GoalRunnerExecutionLease.asWorkerOwnership(parentWorkflowId: String) =
  FeatureTaskRuntimeWorkerOwnership(
    workflowId = parentWorkflowId,
    generation = generation,
    ownerToken = ownerToken,
    hostIdentity = hostIdentity,
    bootIdentity = bootIdentity,
    pid = pid,
    processBirthToken = processBirthToken,
    leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
    heartbeatAt = heartbeatAt.toString(),
    expiresAt = expiresAt.toString(),
    phaseId = "goal_runner",
    phaseAttempt = 1,
  )
