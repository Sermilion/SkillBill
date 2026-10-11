package skillbill.contracts.workflow.identity.status

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys

const val IDE_STATUS_CONTRACT_VERSION: String = "0.3"
const val WORK_BOARD_CONTRACT_VERSION: String = "1.0"
const val WORK_DETAIL_CONTRACT_VERSION: String = "1.0"

object IdeStatusPayloadKeys {
  const val BRANCH_CORRELATION = "branch_correlation"
  const val EXECUTION_SCOPE = "execution_scope"
  const val EXECUTION_ID = "execution_id"
  const val STATUS_STORE_ID = "status_store_id"
  const val RUN_SEQUENCE = "run_sequence"
  const val STATUS_REVISION = "status_revision"
  const val INVOCATION_ID = "invocation_id"
  const val CURRENT_ACTIVITY = "current_activity"
}

object WorkBoardPayloadKeys {
  const val ITEMS = "items"
  const val OBSERVED_AT = "observed_at"
  const val FINISHED_WITHIN = "finished_within"
  const val DIAGNOSTICS = "diagnostics"
  const val SNAPSHOT = "snapshot"
  const val REPOSITORY_ROOT = "repository_root"
  const val FINISHED_AT = WorkflowWirePayloadKeys.FINISHED_AT
  const val FINISHED_AT_SOURCE = "finished_at_source"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val REASON = "reason"
  const val PROBLEM = "problem"
  const val CODE = "code"
  const val MESSAGE = "message"
}

object WorkDetailPayloadKeys {
  const val OBSERVED_AT = WorkBoardPayloadKeys.OBSERVED_AT
  const val ITEM = "item"
  const val PROBLEM = WorkBoardPayloadKeys.PROBLEM
  const val CODE = WorkBoardPayloadKeys.CODE
  const val MESSAGE = WorkBoardPayloadKeys.MESSAGE
  const val FEATURE_TITLE = "feature_title"
  const val SUBTASKS = DecompositionPlanningPayloadKeys.SUBTASKS
  const val NAME = DecompositionPlanningPayloadKeys.NAME
  const val STATUS = SharedPayloadKeys.STATUS
  const val BLOCKED_REASON = DecompositionManifestPayloadKeys.BLOCKED_REASON
  const val DEPENDENCIES = DecompositionPlanningPayloadKeys.DEPENDENCIES
  const val OPTIONAL = DecompositionPlanningPayloadKeys.OPTIONAL
  const val SKIPPED = DecompositionPlanningPayloadKeys.SKIPPED
  const val STEP_PIPELINE = "step_pipeline"
  const val GOAL_FINISH_PIPELINE = "goal_finish_pipeline"
  const val PHASES = "phases"
  const val ATTEMPT_COUNT = WorkflowWirePayloadKeys.ATTEMPT_COUNT
  const val RESOLVED_AGENT = "resolved_agent"
  const val FINISHED = "finished"
  const val EXECUTION_ORIGIN = FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN
  const val CONTINUATION_KIND = "continuation_kind"
  const val LAUNCHED_MODEL = "launched_model"
  const val EFFORT = "effort"
  const val ACTIVE_AGENT = "active_agent"
  const val EXECUTION_LIVENESS = "execution_liveness"
  const val PHASE_ATTEMPT_COUNTS = "phase_attempt_counts"
  const val CUMULATIVE_FIX_ITERATIONS = "cumulative_fix_iterations"
  const val RE_ATTEMPT_CAUSE_COUNTS = "re_attempt_cause_counts"
  const val FINDINGS_IN_SCOPE = "findings_in_scope"
  const val SUPERVISOR_KILL_COUNT = "supervisor_kill_count"
  const val LATEST_OBSERVABILITY_EVENT = "latest_observability_event"
  const val LATEST_WORKTREE_EDIT = "latest_worktree_edit"
  const val COMPLETED_SUBTASK_VALIDATION = "completed_subtask_validation"
  const val PHASE_ID = SharedPayloadKeys.PHASE_ID
  const val CURRENT_PHASE = "current_phase"
  const val COMPLETE_COUNT = "complete_count"
  const val PENDING_COUNT = "pending_count"
  const val BLOCKED_COUNT = "blocked_count"
}

const val GOAL_PLANNING_WAVE_CAP: Int = 5
