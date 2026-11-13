package skillbill.infrastructure.sqlite.core.schema

import skillbill.idestatus.model.IdeStatusDurableWorkflowState
import skillbill.idestatus.model.IdeStatusLifecycleState

internal const val OPERATOR_FINALIZED_AT_COLUMN: String = "operator_finalized_at"
internal const val BRANCH_WORKTREE_RETAINED_PATH_COLUMN: String = "branch_worktree_retained_path"
internal const val BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN: String = "branch_worktree_retained_detail"

internal val STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES: Set<String> =
  setOf(
    IdeStatusLifecycleState.TERMINAL.wireValue,
    IdeStatusDurableWorkflowState.COMPLETED.wireValue,
    "success",
  )

internal val STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES_SQL: String =
  STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES.joinToString(", ") { "'$it'" }
