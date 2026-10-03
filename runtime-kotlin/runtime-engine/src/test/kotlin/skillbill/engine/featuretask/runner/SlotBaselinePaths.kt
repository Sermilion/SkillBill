package skillbill.engine.featuretask.runner

internal object SlotBaselinePaths {
  const val RESOURCE_ROOT = "featuretask/slotbaseline"

  const val STANDALONE = "$RESOURCE_ROOT/standalone"
  const val GOAL_CHILD_BUILD = "$RESOURCE_ROOT/goal-child-build"
  const val GOAL_CHILD_VALIDATE = "$RESOURCE_ROOT/goal-child-validate"
  const val GOAL_PLANNING = "$RESOURCE_ROOT/goal-planning"
  const val CODE_REVIEW = "$RESOURCE_ROOT/code-review"
  const val MCP_LIFECYCLE = "$RESOURCE_ROOT/mcp-lifecycle"
  const val PHASE = "$RESOURCE_ROOT/phase"

  val BUNDLE_DIRECTORIES =
    listOf(STANDALONE, GOAL_CHILD_BUILD, GOAL_CHILD_VALIDATE, GOAL_PLANNING, CODE_REVIEW, MCP_LIFECYCLE, PHASE)

  const val WORKFLOW_SNAPSHOT = "workflow-snapshot.json"
  const val PHASE_RECORDS = "phase-records.json"
  const val LEDGER_ENTRIES = "ledger-entries.json"
  const val HANDOFF_PROJECTIONS = "handoff-projections.json"
  const val RUN_INVARIANTS = "run-invariants.json"
  const val FEATURE_TASK_RUNTIME_FINISHED = "feature-task-runtime-finished.json"
  const val PROMPTS_DIR = "prompts"

  const val PREPLAN_PROMPT = "preplan-prompt.txt"
  const val PLAN_PROMPTS = "plan-prompts.json"
  const val SHARED_PREPLAN_CHECKPOINT = "shared-preplan-checkpoint.json"
  const val PLAN_RECORDS = "plan-records.json"
  const val PLANNING_ATTEMPT_LOG = "planning-attempt-log.json"
  const val PLANNING_LOG = "planning-log.json"

  const val INLINE_OUTPUT = "inline-output.json"
  const val DELEGATED_OUTPUT = "delegated-output.json"
  const val REVIEW_RUNS_INLINE = "review-runs-inline.json"
  const val REVIEW_RUNS_DELEGATED = "review-runs-delegated.json"
  const val REVIEW_TELEMETRY_INLINE = "review-telemetry-inline.json"
  const val REVIEW_TELEMETRY_DELEGATED = "review-telemetry-delegated.json"

  const val QUALITY_CHECK_STARTED = "quality-check-started.json"
  const val QUALITY_CHECK_FINISHED = "quality-check-finished.json"
  const val PR_DESCRIPTION_GENERATED = "pr-description-generated.json"

  const val PHASE_REVIEW_INLINE_OUTPUT = "review-inline-output.json"
  const val PHASE_REVIEW_INLINE_TELEMETRY = "review-inline-telemetry.json"
  const val PHASE_REVIEW_DELEGATED_OUTPUT = "review-delegated-output.json"
  const val PHASE_REVIEW_DELEGATED_TELEMETRY = "review-delegated-telemetry.json"
  const val PHASE_VALIDATION_OUTPUT = "validation-output.json"
  const val PHASE_VALIDATION_TELEMETRY = "validation-telemetry.json"

  const val PHASE_PLAN = "$PHASE/plan"
  const val PHASE_PR = "$PHASE/pr"
  const val PHASE_RUN_OUTPUT = "output.json"
  const val PHASE_RUN_TELEMETRY = "telemetry.json"
  const val PHASE_PLAN_SPEC_BUNDLE = "spec-bundle.json"
}
