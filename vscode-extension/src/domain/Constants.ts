export const IDE_STATUS_CONTRACT_VERSION = "0.3";

export const EXECUTION_SCOPE_WIRE_KEY = "execution_scope";
export const EXECUTION_ID_WIRE_KEY = "execution_id";
export const STATUS_STORE_ID_WIRE_KEY = "status_store_id";
export const BRANCH_CORRELATION_WIRE_KEY = "branch_correlation";
export const RUN_SEQUENCE_WIRE_KEY = "run_sequence";
export const STATUS_REVISION_WIRE_KEY = "status_revision";
export const INVOCATION_ID_WIRE_KEY = "invocation_id";
export const PHASE_ID_WIRE_KEY = "phase_id";
export const CURRENT_ACTIVITY_WIRE_KEY = "current_activity";
export const CURRENT_ACTIVITY_MAX_CODE_POINTS = 160;

export const NO_MATCHING_WORK_REASON_CODE = "no_matching_work";

export const UNCORROBORATED_IDLE_TOLERANCE = 1;

export const POLL_FAILED_REASON_CODE = "poll_failed";

export const PAUSE_REQUESTED_WIRE_KEY = "pause_requested";

export const CURRENT_MODEL_WIRE_KEY = "current_model";

export const CURRENT_PHASE_EXECUTION_WIRE_KEY = "current_phase_execution";

export const CURRENT_PHASE_EXECUTION_KINDS = new Set([
  "pass",
  "semantic_loop",
  "gate_run",
  "bounded_edge",
  "attempt",
]);

export const MODEL_MAX_LENGTH = 120;

export const EFFORT_MAX_LENGTH = 40;

export const PHASE_ID_MAX_LENGTH = 64;

export const MODEL_TEXT_MAX_LENGTH = 60;

export const PAUSED_AT_WIRE_KEY = "paused_at";

export const PAUSE_REASON_WIRE_KEY = "pause_reason";

export const PAUSE_REASON_CODES = new Set([
  "awaiting_operator_decision",
  "operator_request",
  "stop_after_subtask",
  "operator_stop",
  "runner_interrupted",
]);

export const PAUSE_REASON_AWAITING_OPERATOR_DECISION = "awaiting_operator_decision";

export const PAUSE_REASON_LABEL_MAX_LENGTH = 512;

export const ACTIVE_DURATION_MS_WIRE_KEY = "active_duration_ms";

export const ACTIVE_DURATION_AS_OF_WIRE_KEY = "active_duration_as_of";

export const LAST_AGENT_ACTIVITY_AT_WIRE_KEY = "last_agent_activity_at";

export const LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY = "last_agent_activity_label";

export const AGENT_ACTIVITY_LABELS = new Set([
  "worktree write",
  "stdout",
  "durable progress",
  "evidence read",
  "tool stream",
]);

export const FEATURE_GOAL_WORKFLOW_FAMILY = "feature-goal";

export const DEFAULT_REFRESH_INTERVAL_SECONDS = 15;

export const MIN_REFRESH_INTERVAL_SECONDS = 5;

export const MAX_REFRESH_INTERVAL_SECONDS = 3600;

export const DEFAULT_CLI_TIMEOUT_MS = 30_000;

export const DEFAULT_STDOUT_LIMIT_BYTES = 256 * 1024;

export const DEFAULT_STDERR_LIMIT_BYTES = 16 * 1024;

export const GOAL_PAUSE_VERB = ["goal", "pause"] as const;

export const GOAL_STOP_VERB = ["goal", "stop"] as const;

export const REPO_ROOT_OPTION = "--repo-root";
