package skillbill.contracts.workflow.identity.status

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.persistence.WorkBoardReadModelPayloadKeys
import skillbill.contracts.work.WorkIncidentWireKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys
import skillbill.contracts.workflow.payload.WorkflowTimestampPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys

const val IDE_STATUS_CONTRACT_VERSION: String = "0.6"
const val WORK_BOARD_CONTRACT_VERSION: String = "1.3"
const val WORK_DETAIL_CONTRACT_VERSION: String = "1.10"

object WorkBuildPayloadKeys {
  const val BUILD = "build"
  const val STATE = "state"
  const val COMMAND = "command"
  const val SHORT_COMMAND = "short_command"
  const val TOOL = "tool"
  const val PROJECT_KIND = "project_kind"
  const val PID = "pid"
  const val STARTED_AT = WorkflowWirePayloadKeys.STARTED_AT
  const val ELAPSED_MS = "elapsed_ms"
  const val ELAPSED_AS_OF = "elapsed_as_of"
  const val USUAL_MS = "usual_ms"
  const val SAMPLE_COUNT = "sample_count"
  const val EXIT_CODE = WorkDetailPayloadKeys.ACTIVITY_EXIT_CODE
  const val FINISHED_AT = WorkflowWirePayloadKeys.FINISHED_AT
  const val PREVIOUS = "previous"
  const val DURATION_MS = WorkDetailPayloadKeys.ACTIVITY_DURATION_MS
}

object IdeStatusPayloadKeys {
  const val LIFECYCLE_STATE = "lifecycle_state"
  const val BRANCH_CORRELATION = "branch_correlation"
  const val EXECUTION_SCOPE = "execution_scope"
  const val EXECUTION_ID = "execution_id"
  const val STATUS_STORE_ID = "status_store_id"
  const val RUN_SEQUENCE = "run_sequence"
  const val STATUS_REVISION = "status_revision"
  const val INVOCATION_ID = "invocation_id"
  const val CURRENT_ACTIVITY = "current_activity"
  const val QA_OUTCOME = "qa_outcome"
  const val STALLED_SINCE = "stalled_since"
  const val WORKFLOW_QA_OUTCOME = "workflow_qa_outcome"
  const val OUTCOME = "outcome"
  const val REASON = "reason"
  const val FAILURE_KIND = "failure_kind"
  const val RECORDED_AT = "recorded_at"
}

object WorkBoardPayloadKeys {
  const val BUILD = WorkBuildPayloadKeys.BUILD
  const val PAGE_SIZE = "page_size"
  const val NEXT_CURSOR = "next_cursor"
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
  const val BUILD = WorkBuildPayloadKeys.BUILD
  const val PAGE_SIZE = WorkBoardPayloadKeys.PAGE_SIZE
  const val NEXT_CURSOR = WorkBoardPayloadKeys.NEXT_CURSOR
  const val EXECUTION_HISTORY = "execution_history"
  const val EXECUTION_RESULT = "result"
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
  const val PROGRESS_STALLED_SINCE = "progress_stalled_since"
  const val PHASE_ATTEMPT_COUNTS = "phase_attempt_counts"
  const val CUMULATIVE_FIX_ITERATIONS = "cumulative_fix_iterations"
  const val RE_ATTEMPT_CAUSE_COUNTS = "re_attempt_cause_counts"
  const val FINDINGS_IN_SCOPE = "findings_in_scope"
  const val SUPERVISOR_KILL_COUNT = "supervisor_kill_count"
  const val LATEST_OBSERVABILITY_EVENT = "latest_observability_event"
  const val LATEST_WORKTREE_EDIT = "latest_worktree_edit"
  const val COMPLETED_SUBTASK_VALIDATION = "completed_subtask_validation"
  const val GOAL_ASSESSMENT = "goal_assessment"
  const val FIX_RAN = "fix_ran"
  const val PHASE_ID = SharedPayloadKeys.PHASE_ID
  const val CURRENT_PHASE = "current_phase"
  const val COMPLETE_COUNT = "complete_count"
  const val PENDING_COUNT = "pending_count"
  const val BLOCKED_COUNT = "blocked_count"
  const val PLAN_ACTIVITY = "plan_activity"
  const val OUTPUT_RAW = "raw"
  const val ACTIVITY_KIND = "kind"
  const val ACTIVITY_AGENT = "agent_id"
  const val ACTIVITY_PATH = "path"
  const val ACTIVITY_DURATION_MS = "duration_ms"
  const val ACTIVITY_EXIT_CODE = "exit_code"
  const val PLAN_OUTPUT_LINES = "plan_output_lines"
  const val OUTPUT_ID = "id"
  const val OUTPUT_TEXT = "text"
  const val OUTPUT_AT = "at"
  const val OUTPUT_SEQUENCE = "sequence"
  const val PLANNING_WAVES = "waves"
  const val WAVE_NUMBER = "number"
  const val WAVE_SUBTASK_IDS = "subtask_ids"
  const val PLAN_OUTPUT_TAIL = "plan_output_tail"
  const val PLAN_OUTPUT_UPDATED_AT = "plan_output_updated_at"
  const val PLANNING_WAVE_NUMBER = "planning_wave_number"
  const val PLANNING_WAVE_CAPACITY = "planning_wave_capacity"
  const val PLANNING_WAVE_HISTORY_COMPLETE = "planning_wave_history_complete"
  const val PLAN_SUBTASK_LATEST_LINES = "plan_subtask_latest_lines"
  const val STANDALONE_PLAN_PROGRESS = "standalone_plan_progress"
  const val FEATURE_NAME = DecompositionManifestPayloadKeys.FEATURE_NAME
  const val PARENT_SPEC_PATH = DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH
  const val BUNDLE_FILES = "bundle_files"
  const val PLAN_REPORT = WorkIncidentWireKeys.REPORT
  const val PLAN_STAGES = "stages"
  const val PLANNED_SUBTASK_COUNT = DecompositionPlanningPayloadKeys.PLANNED_SUBTASK_COUNT
  const val TOTAL_SUBTASK_COUNT = DecompositionPlanningPayloadKeys.TOTAL_SUBTASK_COUNT
  const val CURRENT_PLANNING_SUBTASK_ID = WorkBoardReadModelPayloadKeys.PLANNING_CURRENT_SUBTASK_ID
  const val PLANNING_WAVE_SUBTASK_IDS = WorkBoardReadModelPayloadKeys.PLANNING_WAVE_SUBTASK_IDS
  const val BLOCKED_SUBTASK_ID = "blocked_subtask_id"
  const val STALLED_SINCE = IdeStatusPayloadKeys.STALLED_SINCE
  const val PLAN_UNITS = "units"
  const val UNIT_STATE = "state"
  const val SPEC_PATH = DecompositionPlanningPayloadKeys.SPEC_PATH
  const val DEPENDS_ON = DecompositionPlanningPayloadKeys.DEPENDS_ON
  const val STARTED_AT = WorkflowWirePayloadKeys.STARTED_AT
  const val FIRST_STARTED_AT = WorkflowTimestampPayloadKeys.FIRST_STARTED_AT
  const val BRIEFING_TEXT = BlockIncidentReportKeys.BRIEFING_TEXT
  const val LATEST_OUTPUT = "latest_output"
  const val LATEST_OUTPUT_AT = "latest_output_at"
}

const val GOAL_PLANNING_WAVE_CAP: Int = 5

object WorkPaginationLimits {
  const val DEFAULT_PAGE_SIZE = 20
  const val MAX_PAGE_SIZE = 100
}
