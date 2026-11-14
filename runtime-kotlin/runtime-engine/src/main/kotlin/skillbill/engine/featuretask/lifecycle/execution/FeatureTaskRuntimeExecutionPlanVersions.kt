package skillbill.engine.featuretask.lifecycle.execution

import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_HISTORICAL_0_2_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION

internal fun executionPlanSupportsStepLaunchAssignments(contractVersion: String): Boolean =
  contractVersion != FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION

internal fun executionPlanSupportsQaSelection(contractVersion: String): Boolean =
  contractVersion != FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION &&
    contractVersion != FEATURE_TASK_RUNTIME_EXECUTION_PLAN_HISTORICAL_0_2_CONTRACT_VERSION

internal fun executionPlanSupportsCrossSlotAllowance(contractVersion: String): Boolean =
  executionPlanSupportsQaSelection(contractVersion)

internal fun executionPlanSupportsProjectCheckCommands(contractVersion: String): Boolean =
  contractVersion == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
