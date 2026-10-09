package dev.skillbill.intellij.domain


const val IDE_STATUS_CONTRACT_VERSION: String = "0.3"

const val EXECUTION_SCOPE_WIRE_KEY: String = "execution_scope"
const val EXECUTION_ID_WIRE_KEY: String = "execution_id"
const val STATUS_STORE_ID_WIRE_KEY: String = "status_store_id"
const val BRANCH_CORRELATION_WIRE_KEY: String = "branch_correlation"
const val RUN_SEQUENCE_WIRE_KEY: String = "run_sequence"
const val STATUS_REVISION_WIRE_KEY: String = "status_revision"
const val INVOCATION_ID_WIRE_KEY: String = "invocation_id"
const val PHASE_ID_WIRE_KEY: String = "phase_id"
const val CURRENT_ACTIVITY_WIRE_KEY: String = "current_activity"
const val CURRENT_ACTIVITY_MAX_CODE_POINTS: Int = 160


const val NO_MATCHING_WORK_REASON_CODE: String = "no_matching_work"


const val UNCORROBORATED_IDLE_TOLERANCE: Int = 1

const val POLL_FAILED_REASON_CODE: String = "poll_failed"


const val PAUSE_REQUESTED_WIRE_KEY: String = "pause_requested"


const val CURRENT_MODEL_WIRE_KEY: String = "current_model"


const val CURRENT_PHASE_EXECUTION_WIRE_KEY: String = "current_phase_execution"


val CURRENT_PHASE_EXECUTION_KINDS: Set<String> = setOf(
    "pass",
    "semantic_loop",
    "gate_run",
    "bounded_edge",
    "attempt",
)


const val MODEL_MAX_LENGTH: Int = 120

const val EFFORT_MAX_LENGTH: Int = 40

const val PHASE_ID_MAX_LENGTH: Int = 64


const val MODEL_TEXT_MAX_LENGTH: Int = 60


const val PAUSED_AT_WIRE_KEY: String = "paused_at"

const val PAUSE_REASON_WIRE_KEY: String = "pause_reason"

val PAUSE_REASON_CODES: Set<String> = setOf(
    "awaiting_operator_decision",
    "operator_request",
    "stop_after_subtask",
    "operator_stop",
    "runner_interrupted",
)

const val PAUSE_REASON_AWAITING_OPERATOR_DECISION: String = "awaiting_operator_decision"

const val PAUSE_REASON_LABEL_MAX_LENGTH: Int = 512

const val GOAL_FINDINGS_DISPLAY_COMMAND: String = "skill-bill goal findings --issue-key"

const val ACTIVE_DURATION_MS_WIRE_KEY: String = "active_duration_ms"

const val ACTIVE_DURATION_AS_OF_WIRE_KEY: String = "active_duration_as_of"

const val LAST_AGENT_ACTIVITY_AT_WIRE_KEY: String = "last_agent_activity_at"

const val LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY: String = "last_agent_activity_label"

val AGENT_ACTIVITY_LABELS: Set<String> = setOf(
    "worktree write",
    "stdout",
    "durable progress",
    "evidence read",
    "tool stream",
)


const val FEATURE_GOAL_WORKFLOW_FAMILY: String = "feature-goal"


val GOAL_PAUSE_VERB: List<String> = listOf("goal", "pause")


val GOAL_STOP_VERB: List<String> = listOf("goal", "stop")


const val REPO_ROOT_OPTION: String = "--repo-root"


const val DEFAULT_REFRESH_INTERVAL_SECONDS: Long = 15L


const val MIN_REFRESH_INTERVAL_SECONDS: Long = 5L

const val MAX_REFRESH_INTERVAL_SECONDS: Long = 3_600L


const val DEFAULT_CLI_TIMEOUT_MS: Long = 30_000L


const val DEFAULT_STDOUT_LIMIT_BYTES: Int = 256 * 1024


const val DEFAULT_STDERR_LIMIT_BYTES: Int = 16 * 1024
