package skillbill.application.workflow.service

import skillbill.workflow.model.WorkflowStatus

internal const val DEFAULT_LIST_LIMIT: Int = 20
internal const val MAX_ABANDONMENT_REASON_LENGTH: Int = 1000
internal const val FEATURE_TASK_RUNTIME_OPERATOR_ABANDONMENT_ARTIFACT_KEY: String = "operator_abandonment"
internal const val FEATURE_TASK_RUNTIME_IDENTITY_REPAIR_ARTIFACT_KEY: String = "operator_identity_repair"
internal val FEATURE_TASK_TERMINAL_STATUSES: Set<WorkflowStatus> = WorkflowStatus.terminalStatuses
