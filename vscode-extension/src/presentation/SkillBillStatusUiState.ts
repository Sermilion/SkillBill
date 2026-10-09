import { CurrentPhaseExecution, CurrentPhaseModel, GoalPlanningInfo, PauseReason } from "../domain/SkillBillStatusOutcome";

export function formatDurationMs(durationMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${minutes.toString().padStart(2, "0")}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds.toString().padStart(2, "0")}s`;
  }
  return `${seconds}s`;
}

export interface StatusUiExecutionMetadata {
  executionScope?: "workflow" | "standalone_phase";
  phaseId?: string;
  currentActivity?: string;
}

export type SkillBillStatusUiState = (
  | {
      kind: "idle";
      headline?: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      lastUpdated?: Date;
      stale?: boolean;
    }
  | {
      kind: "done";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      startedAt?: Date;
      lastUpdated?: Date;
      stale?: boolean;
    }
  | {
      kind: "active";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      workflowId?: string;
      stepLabel: string;
      startedAt?: Date;
      subtaskStartedAt?: Date;
      lastUpdated?: Date;
      planning?: GoalPlanningInfo;
      workflowFamily?: string;
      pauseRequested?: boolean;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      problemSummary?: string;
      activeDurationMs?: number;
      activeDurationAsOf?: Date;
      subtaskActiveDurationMs?: number;
      subtaskActiveDurationAsOf?: Date;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "paused";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      workflowId?: string;
      stepLabel: string;
      startedAt?: Date;
      subtaskStartedAt?: Date;
      lastUpdated?: Date;
      planning?: GoalPlanningInfo;
      workflowFamily?: string;
      pauseRequested?: boolean;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      pauseReason?: PauseReason;
      problemSummary?: string;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "stale";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      workflowId?: string;
      stepLabel?: string;
      startedAt?: Date;
      subtaskStartedAt?: Date;
      lastUpdated?: Date;
      problemSummary?: string;
      planning?: GoalPlanningInfo;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
      lastAgentActivityAt?: Date;
      lastAgentActivityLabel?: string;
    }
  | {
      kind: "blocked";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      workflowId?: string;
      stepLabel?: string;
      startedAt?: Date;
      subtaskStartedAt?: Date;
      lastUpdated?: Date;
      problemSummary?: string;
      stale?: boolean;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
    }
  | {
      kind: "failed";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      issueKey?: string;
      workflowId?: string;
      stepLabel?: string;
      startedAt?: Date;
      subtaskStartedAt?: Date;
      lastUpdated?: Date;
      problemSummary?: string;
      stale?: boolean;
      currentModel?: CurrentPhaseModel;
      currentPhaseExecution?: CurrentPhaseExecution;
    }
  | {
      kind: "unavailable";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      reasonCode: string;
      lastUpdated?: Date;
      problemSummary?: string;
    }
  | {
      kind: "incompatible";
      headline: string;
      detail?: string;
      goalElapsedMs?: number;
      subtaskElapsedMs?: number;
      progressCompleted?: number;
      progressTotal?: number;
      foundContractVersion?: string;
      lastUpdated?: Date;
      problemSummary?: string;
    }) & StatusUiExecutionMetadata;

export function isStaleState(state: SkillBillStatusUiState): boolean {
  if (state.kind === "stale") {
    return true;
  }
  if ("stale" in state) {
    return state.stale === true;
  }
  return false;
}

export function stateIssueKey(state: SkillBillStatusUiState): string | undefined {
  if ("issueKey" in state) {
    return state.issueKey;
  }
  return undefined;
}

export function stateWorkflowId(state: SkillBillStatusUiState): string | undefined {
  if ("workflowId" in state) {
    return state.workflowId;
  }
  return undefined;
}

export function stateStepLabel(state: SkillBillStatusUiState): string | undefined {
  if ("stepLabel" in state) {
    return state.stepLabel;
  }
  return undefined;
}

export function stateDetail(state: SkillBillStatusUiState): string | undefined {
  if ("detail" in state) {
    return state.detail;
  }
  return undefined;
}

export function stateProblemSummary(state: SkillBillStatusUiState): string | undefined {
  if ("problemSummary" in state) {
    return state.problemSummary;
  }
  return undefined;
}

export function statePlanning(state: SkillBillStatusUiState): GoalPlanningInfo | undefined {
  if ("planning" in state) {
    return state.planning;
  }
  return undefined;
}

export function stateCurrentPhaseExecution(state: SkillBillStatusUiState): CurrentPhaseExecution | undefined {
  if ("currentPhaseExecution" in state) {
    return state.currentPhaseExecution;
  }
  return undefined;
}

export function stateCurrentModel(state: SkillBillStatusUiState): CurrentPhaseModel | undefined {
  if ("currentModel" in state) {
    return state.currentModel;
  }
  return undefined;
}

export function stateWorkflowFamily(state: SkillBillStatusUiState): string | undefined {
  if ("workflowFamily" in state) {
    return state.workflowFamily;
  }
  return undefined;
}

export function stateGoalElapsedMs(state: SkillBillStatusUiState): number | undefined {
  if ("goalElapsedMs" in state) {
    return state.goalElapsedMs;
  }
  return undefined;
}

export function stateSubtaskElapsedMs(state: SkillBillStatusUiState): number | undefined {
  if ("subtaskElapsedMs" in state) {
    return state.subtaskElapsedMs;
  }
  return undefined;
}

export function stateProgressCompleted(state: SkillBillStatusUiState): number | undefined {
  if ("progressCompleted" in state) {
    return state.progressCompleted;
  }
  return undefined;
}

export function stateProgressTotal(state: SkillBillStatusUiState): number | undefined {
  if ("progressTotal" in state) {
    return state.progressTotal;
  }
  return undefined;
}

export function stateLastUpdated(state: SkillBillStatusUiState): Date | undefined {
  if ("lastUpdated" in state) {
    return state.lastUpdated;
  }
  return undefined;
}

export function statePauseReason(state: SkillBillStatusUiState): PauseReason | undefined {
  if ("pauseReason" in state) {
    return state.pauseReason;
  }
  return undefined;
}
