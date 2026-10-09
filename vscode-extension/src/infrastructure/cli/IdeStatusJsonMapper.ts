import { redactAbsolutePaths } from "../AbsolutePathGuard";
import {
  ACTIVE_DURATION_AS_OF_WIRE_KEY,
  ACTIVE_DURATION_MS_WIRE_KEY,
  AGENT_ACTIVITY_LABELS,
  BRANCH_CORRELATION_WIRE_KEY,
  CURRENT_MODEL_WIRE_KEY,
  CURRENT_ACTIVITY_MAX_CODE_POINTS,
  CURRENT_ACTIVITY_WIRE_KEY,
  CURRENT_PHASE_EXECUTION_KINDS,
  CURRENT_PHASE_EXECUTION_WIRE_KEY,
  EFFORT_MAX_LENGTH,
  EXECUTION_ID_WIRE_KEY,
  EXECUTION_SCOPE_WIRE_KEY,
  IDE_STATUS_CONTRACT_VERSION,
  INVOCATION_ID_WIRE_KEY,
  LAST_AGENT_ACTIVITY_AT_WIRE_KEY,
  LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY,
  MODEL_MAX_LENGTH,
  NO_MATCHING_WORK_REASON_CODE,
  PAUSED_AT_WIRE_KEY,
  PAUSE_REASON_CODES,
  PAUSE_REASON_LABEL_MAX_LENGTH,
  PAUSE_REASON_WIRE_KEY,
  PAUSE_REQUESTED_WIRE_KEY,
  PHASE_ID_MAX_LENGTH,
  PHASE_ID_WIRE_KEY,
  RUN_SEQUENCE_WIRE_KEY,
  STATUS_REVISION_WIRE_KEY,
  STATUS_STORE_ID_WIRE_KEY,
} from "../../domain/Constants";
import {
  CurrentPhaseExecution,
  CurrentPhaseModel,
  GoalPlanningInfo,
  PauseReason,
  SkillBillStatusOutcome,
  StatusExecutionMetadata,
  UnavailableReason,
} from "../../domain/SkillBillStatusOutcome";

type JsonObject = Record<string, unknown>;

export function mapIdeStatusJson(
  stdout: string,
  observedAt: Date,
  exitCode: number | undefined,
): SkillBillStatusOutcome {
  if (exitCode !== undefined && exitCode !== 0) {
    return {
      kind: "unavailable",
      observedAt,
      summary: "Skill Bill status command failed",
      reasonCode: UnavailableReason.PROCESS_FAILURE,
      diagnostic: { exitCode, reasonCode: "non_zero_exit" },
    };
  }
  const root = parseObject(stdout);
  if (!root) {
    return malformed(observedAt, "malformed_json");
  }
  const contractVersion = getString(root, "contract_version");
  if (!contractVersion) {
    return {
      kind: "incompatible",
      observedAt,
      summary: "IDE status contract version missing",
      foundContractVersion: undefined,
      expectedContractVersion: IDE_STATUS_CONTRACT_VERSION,
      diagnostic: { contractVersionMismatch: true, reasonCode: "missing_contract_version" },
    };
  }
  if (contractVersion !== IDE_STATUS_CONTRACT_VERSION) {
    return {
      kind: "incompatible",
      observedAt,
      summary: "IDE status contract version incompatible",
      foundContractVersion: contractVersion,
      expectedContractVersion: IDE_STATUS_CONTRACT_VERSION,
      diagnostic: {
        contractVersionMismatch: true,
        foundContractVersion: contractVersion,
        reasonCode: "contract_version_mismatch",
      },
    };
  }

  const execution = parseExecution(root);
  if (execution === "invalid") {
    return {
      kind: "incompatible",
      observedAt,
      summary: "IDE status execution identity is malformed",
      foundContractVersion: contractVersion,
      expectedContractVersion: IDE_STATUS_CONTRACT_VERSION,
      diagnostic: { reasonCode: "invalid_execution_identity" },
    };
  }

  const problem = getObject(root, "problem");
  const problemCode = problem ? getString(problem, "code") : undefined;
  if (problemCode === "schema_incompatible") {
    const details = problem ? getObject(problem, "details") : undefined;
    return {
      kind: "incompatible",
      observedAt,
      summary: safeSummary(problem ? getString(problem, "message") : undefined, "Schema incompatible"),
      foundContractVersion: details ? getString(details, "found_contract_version") : undefined,
      expectedContractVersion: IDE_STATUS_CONTRACT_VERSION,
      diagnostic: { contractVersionMismatch: true, reasonCode: problemCode },
    };
  }

  const freshness = getString(root, "freshness");
  const lifecycle = getString(root, "lifecycle_state") ?? "idle";
  const summary = safeSummary(getString(root, "summary"), "Skill Bill status");
  const repositoryIdentity = getString(root, "repository_identity");
  const issueKey = getString(root, "issue_key");
  const workflowId = getString(root, "workflow_id");
  const workflowFamily = getString(root, "workflow_family");
  const step = getObject(root, "current_step");
  const stepId = step ? getString(step, "id") : undefined;
  const stepLabel = step ? getString(step, "label") : undefined;
  const progress = getObject(root, "progress");
  const progressCompleted = progress ? getInt(progress, "completed") : undefined;
  const progressTotal = progress ? getInt(progress, "total") : undefined;
  const startedAt = getInstant(root, "started_at");
  const subtask = getObject(root, "current_subtask");
  const subtaskId = subtask ? getString(subtask, "id") : undefined;
  const subtaskStartedAt = subtask ? getInstant(subtask, "started_at") : undefined;
  const subtaskActiveDurationMs = subtask ? getNonNegativeLong(subtask, ACTIVE_DURATION_MS_WIRE_KEY) : undefined;
  const subtaskActiveDurationAsOf = subtask ? getInstant(subtask, ACTIVE_DURATION_AS_OF_WIRE_KEY) : undefined;
  const updatedAt = getInstant(root, "updated_at");
  const planning = parsePlanning(root);
  const currentModel = parseCurrentModel(root);
  const currentPhaseExecution = parseCurrentPhaseExecution(root);
  const pauseRequested = getBoolean(root, PAUSE_REQUESTED_WIRE_KEY);
  const pausedAt = getInstant(root, PAUSED_AT_WIRE_KEY);
  const pauseReason = parsePauseReason(root);
  const activeDurationMs = getNonNegativeLong(root, ACTIVE_DURATION_MS_WIRE_KEY);
  const activeDurationAsOf = getInstant(root, ACTIVE_DURATION_AS_OF_WIRE_KEY);
  const agentActivity = parseAgentActivity(root);

  if (problemCode === NO_MATCHING_WORK_REASON_CODE) {
    return {
      kind: "idle",
      observedAt,
      summary: safeSummary(problem ? getString(problem, "message") : undefined, summary),
      repositoryIdentity,
      diagnostic: { reasonCode: NO_MATCHING_WORK_REASON_CODE },
    };
  }

  if (problemCode) {
    const unavailable = mapProblemCode(problemCode);
    return {
      kind: "unavailable",
      observedAt,
      summary: safeSummary(problem ? getString(problem, "message") : undefined, summary),
      reasonCode: unavailable,
      diagnostic: { reasonCode: problemCode },
    };
  }

  const isStale = freshness === "stale";

  if (isStale && (lifecycle === "active" || lifecycle === "paused")) {
    return {
      kind: "stale",
      observedAt,
      summary,
      repositoryIdentity,
      issueKey,
      currentStepId: stepId,
      currentStepLabel: stepLabel,
      progressCompleted,
      progressTotal,
      startedAt,
      currentSubtaskId: subtaskId,
      subtaskStartedAt,
      updatedAt,
      fromCache: false,
      planning,
      activeDurationMs,
      activeDurationAsOf,
      subtaskActiveDurationMs,
      subtaskActiveDurationAsOf,
      currentModel,
      currentPhaseExecution,
      ...execution,
      lastAgentActivityAt: agentActivity?.at,
      lastAgentActivityLabel: agentActivity?.label,
    };
  }

  switch (lifecycle) {
    case "active":
    case "paused": {
      if (!repositoryIdentity || !stepId || !stepLabel || !updatedAt) {
        return malformed(observedAt, "incomplete_active_payload");
      }
      const base = {
        observedAt,
        summary,
        repositoryIdentity,
        issueKey,
        workflowId,
        workflowFamily,
        currentStepId: stepId,
        currentStepLabel: stepLabel,
        progressCompleted,
        progressTotal,
        startedAt,
        currentSubtaskId: subtaskId,
        subtaskStartedAt,
        updatedAt,
        planning,
        pauseRequested,
        pausedAt,
        activeDurationMs,
        activeDurationAsOf,
        subtaskActiveDurationMs,
        subtaskActiveDurationAsOf,
        currentModel,
        currentPhaseExecution,
        ...execution,
        lastAgentActivityAt: agentActivity?.at,
        lastAgentActivityLabel: agentActivity?.label,
      };
      if (lifecycle === "paused") {
        return { kind: "paused", ...base, pauseReason };
      }
      return { kind: "active", ...base };
    }
    case "blocked":
      return {
        kind: "blocked",
        observedAt,
        summary,
        repositoryIdentity,
        issueKey,
        currentStepId: stepId,
        currentStepLabel: stepLabel,
        startedAt,
        currentSubtaskId: subtaskId,
        subtaskStartedAt,
        updatedAt,
        stale: isStale,
        activeDurationMs,
        activeDurationAsOf,
        subtaskActiveDurationMs,
        subtaskActiveDurationAsOf,
        currentModel,
        currentPhaseExecution,
        ...execution,
      };
    case "failed":
      return {
        kind: "failed",
        observedAt,
        summary,
        repositoryIdentity,
        issueKey,
        currentStepId: stepId,
        currentStepLabel: stepLabel,
        startedAt,
        currentSubtaskId: subtaskId,
        subtaskStartedAt,
        updatedAt,
        stale: isStale,
        activeDurationMs,
        activeDurationAsOf,
        subtaskActiveDurationMs,
        subtaskActiveDurationAsOf,
        currentModel,
        currentPhaseExecution,
        ...execution,
      };
    case "idle":
      return {
        kind: "idle",
        observedAt,
        summary,
        repositoryIdentity,
        stale: isStale,
      };
    case "terminal":
      return {
        kind: "done",
        observedAt,
        summary,
        repositoryIdentity,
        issueKey,
        progressCompleted,
        progressTotal,
        startedAt,
        updatedAt,
        stale: isStale,
        activeDurationMs,
        activeDurationAsOf,
        ...execution,
      };
    default:
      return {
        kind: "unavailable",
        observedAt,
        summary: "Unknown lifecycle state",
        reasonCode: UnavailableReason.MALFORMED_OUTPUT,
        diagnostic: { reasonCode: "unknown_lifecycle" },
      };
  }
}

function malformed(observedAt: Date, code: string): SkillBillStatusOutcome {
  return {
    kind: "unavailable",
    observedAt,
    summary: "Malformed Skill Bill status output",
    reasonCode: UnavailableReason.MALFORMED_OUTPUT,
    diagnostic: { reasonCode: code },
  };
}

function mapProblemCode(code: string): UnavailableReason {
  switch (code) {
    case "missing_repository_identity":
      return UnavailableReason.MISSING_REPOSITORY;
    case "absent_database":
      return UnavailableReason.ABSENT_DATABASE;
    case "no_matching_work":
      return UnavailableReason.NO_MATCHING_WORK;
    case "invalid_repository_input":
      return UnavailableReason.INVALID_REPOSITORY_INPUT;
    case "incompatible_record":
      return UnavailableReason.MISCONFIGURED;
    default:
      return UnavailableReason.MISCONFIGURED;
  }
}

function parseObject(stdout: string): JsonObject | undefined {
  try {
    const parsed: unknown = JSON.parse(stdout.trim());
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as JsonObject;
    }
    return undefined;
  } catch {
    return undefined;
  }
}

function safeSummary(raw: string | undefined, fallback: string): string {
  const value = raw?.trim() ?? "";
  if (!value) {
    return fallback;
  }
  return redactAbsolutePaths(value).slice(0, 512);
}

function parsePlanning(root: JsonObject): GoalPlanningInfo | undefined {
  const planning = getObject(root, "planning");
  if (!planning) {
    return undefined;
  }
  const state = getString(planning, "state")?.trim();
  if (!state) {
    return undefined;
  }
  const sharedPreplanPrepared = getBoolean(planning, "shared_preplan_prepared");
  if (sharedPreplanPrepared === undefined) {
    return undefined;
  }
  const planned = getStrictInt(planning, "planned_subtask_count");
  const total = getStrictInt(planning, "total_subtask_count");
  if (planned === undefined || planned < 0 || total === undefined || total < 0) {
    return undefined;
  }
  const reasonRaw = getString(planning, "reason");
  const reason = reasonRaw ? safeSummary(reasonRaw, "").trim() || undefined : undefined;
  return {
    state,
    sharedPreplanPrepared,
    plannedSubtaskCount: planned,
    totalSubtaskCount: total,
    currentPlanningSubtaskId: getString(planning, "current_planning_subtask_id")?.trim() || undefined,
    reason,
  };
}

function parseCurrentModel(root: JsonObject): CurrentPhaseModel | undefined {
  const currentModel = getObject(root, CURRENT_MODEL_WIRE_KEY);
  if (!currentModel) {
    return undefined;
  }
  const model = boundedString(currentModel, "model", MODEL_MAX_LENGTH);
  if (!model) {
    return undefined;
  }
  return {
    model,
    effort: boundedString(currentModel, "effort", EFFORT_MAX_LENGTH),
    phaseId: boundedString(currentModel, "phase_id", PHASE_ID_MAX_LENGTH),
  };
}

function parseCurrentPhaseExecution(root: JsonObject): CurrentPhaseExecution | undefined {
  const execution = getObject(root, CURRENT_PHASE_EXECUTION_WIRE_KEY);
  if (!execution) {
    return undefined;
  }
  const phaseId = boundedString(execution, "phase_id", PHASE_ID_MAX_LENGTH);
  if (!phaseId) {
    return undefined;
  }
  const kind = getStringPrimitive(execution, "kind");
  if (!kind || !CURRENT_PHASE_EXECUTION_KINDS.has(kind)) {
    return undefined;
  }
  const count = getStrictInt(execution, "count");
  if (count === undefined || count < 1) {
    return undefined;
  }
  const totalElement = execution.total;
  let total: number | undefined;
  if (totalElement === undefined) {
    total = undefined;
  } else if (totalElement === null) {
    return undefined;
  } else if (kind !== "bounded_edge") {
    return undefined;
  } else {
    const parsedTotal = getStrictInt(execution, "total");
    if (parsedTotal === undefined || parsedTotal < 1) {
      return undefined;
    }
    total = parsedTotal;
  }
  return { phaseId, kind, count, total };
}

function parsePauseReason(root: JsonObject): PauseReason | undefined {
  const reason = getObject(root, PAUSE_REASON_WIRE_KEY);
  if (!reason) {
    return undefined;
  }
  const code = getStringPrimitive(reason, "code");
  if (!code || !PAUSE_REASON_CODES.has(code)) {
    return undefined;
  }
  return {
    code,
    label: boundedString(reason, "label", PAUSE_REASON_LABEL_MAX_LENGTH),
  };
}

function parseAgentActivity(root: JsonObject): { at: Date; label: string } | undefined {
  const at = getInstant(root, LAST_AGENT_ACTIVITY_AT_WIRE_KEY);
  const label = getString(root, LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY);
  if (!at || !label || !AGENT_ACTIVITY_LABELS.has(label)) {
    return undefined;
  }
  return { at, label };
}

function parseExecution(root: JsonObject): StatusExecutionMetadata | "invalid" | undefined {
  const keys = [
    EXECUTION_SCOPE_WIRE_KEY,
    EXECUTION_ID_WIRE_KEY,
    STATUS_STORE_ID_WIRE_KEY,
    RUN_SEQUENCE_WIRE_KEY,
    STATUS_REVISION_WIRE_KEY,
    INVOCATION_ID_WIRE_KEY,
    PHASE_ID_WIRE_KEY,
  ];
  if (!keys.some((key) => key in root) && !(CURRENT_ACTIVITY_WIRE_KEY in root)) {
    return undefined;
  }
  const executionScope = getString(root, EXECUTION_SCOPE_WIRE_KEY);
  const executionId = getString(root, EXECUTION_ID_WIRE_KEY);
  const statusStoreId = getString(root, STATUS_STORE_ID_WIRE_KEY);
  const branchCorrelation = getString(root, BRANCH_CORRELATION_WIRE_KEY);
  const runSequence = getString(root, RUN_SEQUENCE_WIRE_KEY);
  const statusRevision = getString(root, STATUS_REVISION_WIRE_KEY);
  const invocationId = getString(root, INVOCATION_ID_WIRE_KEY);
  const phaseId = boundedString(root, PHASE_ID_WIRE_KEY, PHASE_ID_MAX_LENGTH);
  const invocationFieldValid = !(INVOCATION_ID_WIRE_KEY in root) || invocationId !== undefined;
  const phaseFieldValid = !(PHASE_ID_WIRE_KEY in root) || phaseId !== undefined;
  if (
    (executionScope !== "workflow" && executionScope !== "standalone_phase") ||
    !executionId ||
    !statusStoreId ||
    !runSequence ||
    !statusRevision ||
    !/^[1-9][0-9]*$/.test(runSequence) ||
    !/^[1-9][0-9]*$/.test(statusRevision) ||
    !invocationFieldValid ||
    !phaseFieldValid ||
    (executionScope === "workflow" && (invocationId !== undefined || phaseId !== undefined)) ||
    (executionScope === "standalone_phase" && (!invocationId || !phaseId))
  ) {
    return "invalid";
  }
  const currentActivity = boundedCodePointString(root, CURRENT_ACTIVITY_WIRE_KEY);
  if (CURRENT_ACTIVITY_WIRE_KEY in root && !currentActivity) {
    return "invalid";
  }
  return {
    executionScope,
    executionId,
    statusStoreId,
    branchCorrelation,
    runSequence,
    statusRevision,
    invocationId,
    phaseId,
    currentActivity,
  };
}

function boundedCodePointString(root: JsonObject, key: string): string | undefined {
  const value = getString(root, key)?.trim();
  if (!value || [...value].some((character) => /[\u0000-\u001F\u007F]/.test(character))) {
    return undefined;
  }
  const codePoints = [...value];
  return codePoints.length <= CURRENT_ACTIVITY_MAX_CODE_POINTS ? value : undefined;
}

function boundedString(obj: JsonObject, key: string, maxLength: number): string | undefined {
  const raw = getStringPrimitive(obj, key)?.trim();
  if (!raw || raw.length > maxLength) {
    return undefined;
  }
  return raw;
}

function getObject(obj: JsonObject, key: string): JsonObject | undefined {
  const value = obj[key];
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as JsonObject;
  }
  return undefined;
}

function getString(obj: JsonObject, key: string): string | undefined {
  const value = obj[key];
  if (typeof value === "string") {
    return value;
  }
  return undefined;
}

function getStringPrimitive(obj: JsonObject, key: string): string | undefined {
  const value = obj[key];
  return typeof value === "string" ? value : undefined;
}

function getInt(obj: JsonObject, key: string): number | undefined {
  const value = obj[key];
  if (typeof value === "number" && Number.isInteger(value)) {
    return value;
  }
  return undefined;
}

function getStrictInt(obj: JsonObject, key: string): number | undefined {
  const value = obj[key];
  if (typeof value !== "number" || !Number.isInteger(value)) {
    return undefined;
  }
  return value;
}

function getBoolean(obj: JsonObject, key: string): boolean | undefined {
  const value = obj[key];
  return typeof value === "boolean" ? value : undefined;
}

function getNonNegativeLong(obj: JsonObject, key: string): number | undefined {
  const value = obj[key];
  if (typeof value === "number" && Number.isInteger(value) && value >= 0) {
    return value;
  }
  return undefined;
}

function getInstant(obj: JsonObject, key: string): Date | undefined {
  const raw = getString(obj, key);
  if (!raw) {
    return undefined;
  }
  const parsed = Date.parse(raw);
  if (Number.isNaN(parsed)) {
    return undefined;
  }
  return new Date(parsed);
}
