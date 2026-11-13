package skillbill.infrastructure.sqlite.core.schema

import skillbill.idestatus.model.IdeStatusLifecycleState

internal const val OPERATOR_FINALIZED_AT_COLUMN: String = "operator_finalized_at"

internal val STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES_SQL: String =
  listOf(
    IdeStatusLifecycleState.TERMINAL.wireValue,
    "completed",
    "success",
  ).joinToString(", ") { "'$it'" }
