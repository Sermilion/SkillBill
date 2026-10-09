import { IDE_STATUS_CONTRACT_VERSION, NO_MATCHING_WORK_REASON_CODE, POLL_FAILED_REASON_CODE } from "./Constants";

export interface StatusDiagnostic {
  exitCode?: number;
  timedOut?: boolean;
  cancelled?: boolean;
  contractVersionMismatch?: boolean;
  foundContractVersion?: string;
  reasonCode?: string;
}

export interface GoalPlanningInfo {
  state: string;
  sharedPreplanPrepared: boolean;
  plannedSubtaskCount: number;
  totalSubtaskCount: number;
  currentPlanningSubtaskId?: string;
  reason?: string;
}

export interface CurrentPhaseModel {
  model: string;
  effort?: string;
  phaseId?: string;
}

export interface CurrentPhaseExecution {
  phaseId: string;
  kind: string;
  count: number;
  total?: number;
}

export interface PauseReason {
  code: string;
  label?: string;
}

export interface StatusExecutionMetadata {
  executionScope?: "workflow" | "standalone_phase";
  executionId?: string;
  statusStoreId?: string;
  branchCorrelation?: string;
  runSequence?: string;
  statusRevision?: string;
  invocationId?: string;
  phaseId?: string;
  currentActivity?: string;
}

export enum UnavailableReason {
  MISSING_EXECUTABLE = "MISSING_EXECUTABLE",
  MISCONFIGURED = "MISCONFIGURED",
  MISSING_REPOSITORY = "MISSING_REPOSITORY",
  ABSENT_DATABASE = "ABSENT_DATABASE",
  NO_MATCHING_WORK = "NO_MATCHING_WORK",
  INVALID_REPOSITORY_INPUT = "INVALID_REPOSITORY_INPUT",
  PROCESS_FAILURE = "PROCESS_FAILURE",
  TIMEOUT = "TIMEOUT",
  CANCELLED = "CANCELLED",
  MALFORMED_OUTPUT = "MALFORMED_OUTPUT",
}

export type SkillBillStatusOutcome = (
  | {
      kind: "idle";
      observedAt: Date;
      summary: string;
      repositoryIdentity?: string;
      stale?: boolean;
      diagnostic?: StatusDiagnostic;
    }
  | {
      kind: "done";
      observedAt: Date;
      summary: string;
      repositoryIdentity?: string;
      issueKey?: string;
      progressCompleted?: number;
      progressTotal?: number;
      startedAt?: Date;
      updatedAt?: Date;
      stale?: boolean;
      diagnostic?: StatusDiagnostic;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
    }
  | {
      kind: "active";
      observedAt: Date;
      summary: string;
      repositoryIdentity: string;
      issueKey?: string;
      workflowId?: string;
      workflowFamily?: string;
      currentStepId: string;
      currentStepLabel: string;
      progressCompleted?: number;
      progressTotal?: number;
      startedAt?: Date;
      currentSubtaskId?: string;
      subtaskStartedAt?: Date;
      updatedAt: Date;
      diagnostic?: StatusDiagnostic;
      planning?: GoalPlanningInfo;
      pauseRequested?: boolean;
      pausedAt?: Date;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "paused";
      observedAt: Date;
      summary: string;
      repositoryIdentity: string;
      issueKey?: string;
      workflowId?: string;
      workflowFamily?: string;
      currentStepId: string;
      currentStepLabel: string;
      progressCompleted?: number;
      progressTotal?: number;
      startedAt?: Date;
      currentSubtaskId?: string;
      subtaskStartedAt?: Date;
      updatedAt: Date;
      diagnostic?: StatusDiagnostic;
      planning?: GoalPlanningInfo;
      pauseRequested?: boolean;
      pausedAt?: Date;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      pauseReason?: PauseReason;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "stale";
      observedAt: Date;
      summary: string;
      repositoryIdentity?: string;
      issueKey?: string;
      currentStepId?: string;
      currentStepLabel?: string;
      progressCompleted?: number;
      progressTotal?: number;
      startedAt?: Date;
      currentSubtaskId?: string;
      subtaskStartedAt?: Date;
      updatedAt?: Date;
      fromCache?: boolean;
      diagnostic?: StatusDiagnostic;
      planning?: GoalPlanningInfo;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "blocked";
      observedAt: Date;
      summary: string;
      repositoryIdentity?: string;
      issueKey?: string;
      currentStepId?: string;
      currentStepLabel?: string;
      startedAt?: Date;
      currentSubtaskId?: string;
      subtaskStartedAt?: Date;
      updatedAt?: Date;
      stale?: boolean;
      diagnostic?: StatusDiagnostic;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
    }
  | {
      kind: "failed";
      observedAt: Date;
      summary: string;
      repositoryIdentity?: string;
      issueKey?: string;
      currentStepId?: string;
      currentStepLabel?: string;
      startedAt?: Date;
      currentSubtaskId?: string;
      subtaskStartedAt?: Date;
      updatedAt?: Date;
      stale?: boolean;
      diagnostic?: StatusDiagnostic;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
    }
  | {
      kind: "unavailable";
      observedAt: Date;
      summary: string;
      reasonCode: UnavailableReason;
      diagnostic?: StatusDiagnostic;
    }
  | {
      kind: "incompatible";
      observedAt: Date;
      summary: string;
      foundContractVersion?: string;
      expectedContractVersion?: string;
      diagnostic?: StatusDiagnostic;
    }) & StatusExecutionMetadata;

export function isUncorroboratedIdle(outcome: SkillBillStatusOutcome): boolean {
  return outcome.kind === "idle" && outcome.diagnostic?.reasonCode === NO_MATCHING_WORK_REASON_CODE;
}

export function isLiveOutcome(outcome: SkillBillStatusOutcome): boolean {
  switch (outcome.kind) {
    case "active":
    case "paused":
    case "blocked":
    case "failed":
    case "stale":
      return true;
    default:
      return false;
  }
}

export function isPollTransportFailure(reason: UnavailableReason): boolean {
  return (
    reason === UnavailableReason.TIMEOUT ||
    reason === UnavailableReason.CANCELLED ||
    reason === UnavailableReason.PROCESS_FAILURE
  );
}

export function withPollFailure(
  outcome: SkillBillStatusOutcome,
  reason: UnavailableReason,
): SkillBillStatusOutcome {
  const marker: StatusDiagnostic = {
    timedOut: reason === UnavailableReason.TIMEOUT,
    cancelled: reason === UnavailableReason.CANCELLED,
    reasonCode: POLL_FAILED_REASON_CODE,
  };
  switch (outcome.kind) {
    case "active":
    case "paused":
    case "blocked":
    case "failed":
    case "stale":
      return { ...outcome, kind: "stale", diagnostic: marker };
    default:
      return outcome;
  }
}

export { IDE_STATUS_CONTRACT_VERSION, NO_MATCHING_WORK_REASON_CODE };
