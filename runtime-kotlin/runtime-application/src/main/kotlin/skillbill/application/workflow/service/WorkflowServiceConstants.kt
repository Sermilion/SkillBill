package skillbill.application.workflow.service

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.workflow.model.WorkflowStatus

internal const val DEFAULT_LIST_LIMIT: Int = 20
internal const val MAX_ABANDONMENT_REASON_LENGTH: Int = 1000
internal const val FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY: String = "operator_abandonment"
internal const val FEATURE_TASK_RUNTIME_IDENTITY_REPAIR_ARTIFACT_KEY: String = "operator_identity_repair"
internal const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
internal val FEATURE_TASK_FAMILY_KINDS = setOf(WorkflowFamilyKind.TASK_RUNTIME)
internal val FEATURE_TASK_TERMINAL_STATUSES: Set<WorkflowStatus> = WorkflowStatus.terminalStatuses
internal const val INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR =
  "Feature-task workflows must be opened through openFeatureTask with complete immutable execution identity."
internal const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"
