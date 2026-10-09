import { POLL_FAILED_REASON_CODE } from "../domain/Constants";
import { SkillBillStatusOutcome, StatusDiagnostic, GoalPlanningInfo } from "../domain/SkillBillStatusOutcome";
import { SkillBillStatusUiState } from "./SkillBillStatusUiState";

export const StatusUiMapper = {
  POLL_TIMEOUT_NOTE: "Status poll timed out. Showing the last live snapshot.",
  POLL_CANCELLED_NOTE: "Status poll was cancelled. Showing the last live snapshot.",
  POLL_FAILED_NOTE: "Status poll failed. Showing the last live snapshot.",

  map(outcome: SkillBillStatusOutcome, now: Date): SkillBillStatusUiState {
    switch (outcome.kind) {
      case "idle":
        return {
          kind: "idle",
          headline: "Skill Bill: idle",
          detail: outcome.summary,
          lastUpdated: outcome.observedAt,
          stale: outcome.stale,
        };
      case "done":
        return {
          kind: "done",
          headline: doneHeadline(outcome.issueKey),
          detail: outcome.summary,
          goalElapsedMs: StatusUiMapper.activeElapsedMs(
            outcome.activeDurationMs,
            outcome.activeDurationAsOf,
            outcome.startedAt,
            settledAt(outcome.updatedAt, now),
          ),
          progressCompleted: outcome.progressCompleted,
          progressTotal: outcome.progressTotal,
          issueKey: outcome.issueKey,
          startedAt: outcome.startedAt,
          lastUpdated: outcome.updatedAt ?? outcome.observedAt,
          stale: outcome.stale,
          executionScope: outcome.executionScope,
          phaseId: outcome.phaseId,
          currentActivity: outcome.currentActivity,
        };
      case "active":
        return mapRunState("active", outcome, now);
      case "paused":
        return mapRunState("paused", outcome, now);
      case "stale":
        return {
          kind: "stale",
          headline: staleHeadline(outcome.currentStepLabel),
          detail: outcome.summary,
          goalElapsedMs: StatusUiMapper.activeElapsedMs(
            outcome.activeDurationMs,
            outcome.activeDurationAsOf,
            outcome.startedAt,
            settledAt(outcome.updatedAt, now),
          ),
          subtaskElapsedMs: StatusUiMapper.subtaskElapsedMs(
            outcome.subtaskActiveDurationMs,
            outcome.subtaskActiveDurationAsOf,
            outcome.subtaskStartedAt,
            outcome.activeDurationMs,
            outcome.activeDurationAsOf,
            outcome.startedAt,
            settledAt(outcome.updatedAt, now),
          ),
          progressCompleted: outcome.progressCompleted,
          progressTotal: outcome.progressTotal,
          issueKey: outcome.issueKey,
          stepLabel: outcome.currentStepLabel,
          startedAt: outcome.startedAt,
          subtaskStartedAt: outcome.subtaskStartedAt,
          lastUpdated: outcome.updatedAt ?? outcome.observedAt,
          problemSummary: problemSummaryWith(
            outcome.fromCache ? "Cached status is stale" : "Status is stale",
            outcome.diagnostic,
          ),
          planning: relevantPlanning(outcome.planning, outcome.currentSubtaskId, outcome.progressCompleted),
          currentModel: outcome.currentModel,
          currentPhaseExecution: outcome.currentPhaseExecution,
          lastAgentActivityAt: outcome.lastAgentActivityAt,
          lastAgentActivityLabel: outcome.lastAgentActivityLabel,
          executionScope: outcome.executionScope,
          phaseId: outcome.phaseId,
          currentActivity: outcome.currentActivity,
        };
      case "blocked":
        return mapSettledRun("blocked", outcome, now);
      case "failed":
        return mapSettledRun("failed", outcome, now);
      case "unavailable":
        return {
          kind: "unavailable",
          headline: "Skill Bill: unavailable",
          detail: outcome.summary,
          reasonCode: outcome.reasonCode,
          lastUpdated: outcome.observedAt,
          problemSummary: `${outcome.reasonCode}: ${outcome.summary}`,
        };
      case "incompatible": {
        let problem = "Contract mismatch";
        if (outcome.foundContractVersion) {
          problem += ` (found ${outcome.foundContractVersion})`;
        }
        problem += `: ${outcome.summary}`;
        return {
          kind: "incompatible",
          headline: "Skill Bill: incompatible",
          detail: outcome.summary,
          foundContractVersion: outcome.foundContractVersion,
          lastUpdated: outcome.observedAt,
          problemSummary: problem,
        };
      }
    }
  },

  settledAt(updatedAt: Date | undefined, now: Date): Date {
    return updatedAt ?? now;
  },

  elapsedMs(startedAt: Date | undefined, now: Date): number | undefined {
    if (!startedAt) {
      return undefined;
    }
    const millis = now.getTime() - startedAt.getTime();
    return millis <= 0 ? 0 : millis;
  },

  activeElapsedMs(
    accumulatedMs: number | undefined,
    asOf: Date | undefined,
    startedAt: Date | undefined,
    now: Date,
  ): number | undefined {
    if (accumulatedMs === undefined) {
      return this.elapsedMs(startedAt, now);
    }
    const tailMillis = asOf ? Math.max(0, now.getTime() - asOf.getTime()) : 0;
    return accumulatedMs + tailMillis;
  },

  subtaskElapsedMs(
    subtaskAccumulatedMs: number | undefined,
    subtaskAsOf: Date | undefined,
    subtaskStartedAt: Date | undefined,
    goalAccumulatedMs: number | undefined,
    goalAsOf: Date | undefined,
    goalStartedAt: Date | undefined,
    now: Date,
  ): number | undefined {
    const goalElapsed = this.activeElapsedMs(goalAccumulatedMs, goalAsOf, goalStartedAt, now);
    const raw =
      subtaskAccumulatedMs !== undefined
        ? this.activeElapsedMs(subtaskAccumulatedMs, subtaskAsOf, subtaskStartedAt, now)
        : this.elapsedMs(subtaskStartedAt, now);
    if (raw === undefined) {
      return undefined;
    }
    return goalElapsed !== undefined ? Math.min(raw, goalElapsed) : raw;
  },

  withElapsed(state: SkillBillStatusUiState, now: Date): SkillBillStatusUiState {
    if (state.kind !== "active") {
      return state;
    }
    return {
      ...state,
      goalElapsedMs: this.activeElapsedMs(
        state.activeDurationMs,
        state.activeDurationAsOf,
        state.startedAt,
        now,
      ),
      subtaskElapsedMs: this.subtaskElapsedMs(
        state.subtaskActiveDurationMs,
        state.subtaskActiveDurationAsOf,
        state.subtaskStartedAt,
        state.activeDurationMs,
        state.activeDurationAsOf,
        state.startedAt,
        now,
      ),
    };
  },
};

function mapRunState(
  kind: "active" | "paused",
  outcome: Extract<SkillBillStatusOutcome, { kind: "active" }> | Extract<SkillBillStatusOutcome, { kind: "paused" }>,
  now: Date,
): SkillBillStatusUiState {
  const settled = kind === "paused" ? settledAt(outcome.updatedAt, now) : now;
  const base = {
    headline:
      kind === "active"
        ? activeHeadline(outcome.issueKey, outcome.currentStepLabel)
        : pausedHeadline(outcome.issueKey),
    detail: outcome.summary,
    goalElapsedMs: StatusUiMapper.activeElapsedMs(
      outcome.activeDurationMs,
      outcome.activeDurationAsOf,
      outcome.startedAt,
      settled,
    ),
    subtaskElapsedMs: StatusUiMapper.subtaskElapsedMs(
      outcome.subtaskActiveDurationMs,
      outcome.subtaskActiveDurationAsOf,
      outcome.subtaskStartedAt,
      outcome.activeDurationMs,
      outcome.activeDurationAsOf,
      outcome.startedAt,
      settled,
    ),
    progressCompleted: outcome.progressCompleted,
    progressTotal: outcome.progressTotal,
    issueKey: outcome.issueKey,
    workflowId: outcome.workflowId,
    stepLabel: outcome.currentStepLabel,
    startedAt: outcome.startedAt,
    subtaskStartedAt: outcome.subtaskStartedAt,
    lastUpdated: outcome.updatedAt,
    planning: relevantPlanning(outcome.planning, outcome.currentSubtaskId, outcome.progressCompleted),
    workflowFamily: outcome.workflowFamily,
    pauseRequested: outcome.pauseRequested,
    currentModel: outcome.currentModel,
    currentPhaseExecution: outcome.currentPhaseExecution,
    problemSummary: problemSummaryWith(undefined, outcome.diagnostic),
    activeDurationMs: outcome.activeDurationMs,
    activeDurationAsOf: outcome.activeDurationAsOf,
    subtaskActiveDurationMs: outcome.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf: outcome.subtaskActiveDurationAsOf,
    lastAgentActivityAt: outcome.lastAgentActivityAt,
    lastAgentActivityLabel: outcome.lastAgentActivityLabel,
    executionScope: outcome.executionScope,
    phaseId: outcome.phaseId,
    currentActivity: outcome.currentActivity,
  };
  if (kind === "paused" && outcome.kind === "paused") {
    return { kind: "paused", ...base, pauseReason: outcome.pauseReason };
  }
  return { kind: "active", ...base };
}

function mapSettledRun(
  kind: "blocked" | "failed",
  outcome: Extract<SkillBillStatusOutcome, { kind: "blocked" | "failed" }>,
  now: Date,
): SkillBillStatusUiState {
  const settled = settledAt(outcome.updatedAt, now);
  const base = {
    headline: kind === "blocked" ? "Skill Bill: blocked" : "Skill Bill: failed",
    detail: outcome.summary,
    goalElapsedMs: StatusUiMapper.activeElapsedMs(
      outcome.activeDurationMs,
      outcome.activeDurationAsOf,
      outcome.startedAt,
      settled,
    ),
    subtaskElapsedMs: StatusUiMapper.subtaskElapsedMs(
      outcome.subtaskActiveDurationMs,
      outcome.subtaskActiveDurationAsOf,
      outcome.subtaskStartedAt,
      outcome.activeDurationMs,
      outcome.activeDurationAsOf,
      outcome.startedAt,
      settled,
    ),
    issueKey: outcome.issueKey,
    stepLabel: outcome.currentStepLabel,
    startedAt: outcome.startedAt,
    subtaskStartedAt: outcome.subtaskStartedAt,
    lastUpdated: outcome.updatedAt ?? outcome.observedAt,
    problemSummary: problemSummaryWith(outcome.summary, outcome.diagnostic),
    stale: outcome.stale,
    currentModel: outcome.currentModel,
    currentPhaseExecution: outcome.currentPhaseExecution,
    executionScope: outcome.executionScope,
    phaseId: outcome.phaseId,
    currentActivity: outcome.currentActivity,
  };
  return { kind, ...base };
}

function problemSummaryWith(base: string | undefined, diagnostic: StatusDiagnostic | undefined): string | undefined {
  const note = pollFailureNote(diagnostic);
  if (!note) {
    return base;
  }
  if (!base?.trim()) {
    return note;
  }
  return `${base} ${note}`;
}

function pollFailureNote(diagnostic: StatusDiagnostic | undefined): string | undefined {
  if (diagnostic?.reasonCode !== POLL_FAILED_REASON_CODE) {
    return undefined;
  }
  if (diagnostic.timedOut) {
    return StatusUiMapper.POLL_TIMEOUT_NOTE;
  }
  if (diagnostic.cancelled) {
    return StatusUiMapper.POLL_CANCELLED_NOTE;
  }
  return StatusUiMapper.POLL_FAILED_NOTE;
}

function relevantPlanning(
  planning: GoalPlanningInfo | undefined,
  currentSubtaskId: string | undefined,
  progressCompleted: number | undefined,
): GoalPlanningInfo | undefined {
  if (!planning || planning.state === "prepared") {
    return undefined;
  }
  if (currentSubtaskId || (progressCompleted ?? 0) > 0) {
    return undefined;
  }
  return planning;
}

function doneHeadline(issueKey: string | undefined): string {
  const key = issueKey ? `${issueKey} · ` : "";
  return `Skill Bill: ${key}done`;
}

function activeHeadline(issueKey: string | undefined, stepLabel: string): string {
  const key = issueKey ? `${issueKey} · ` : "";
  return `Skill Bill: ${key}${stepLabel}`;
}

function pausedHeadline(issueKey: string | undefined): string {
  const key = issueKey ? `${issueKey} · ` : "";
  return `Skill Bill: ${key}paused`;
}

function staleHeadline(stepLabel: string | undefined): string {
  if (!stepLabel?.trim()) {
    return "Skill Bill: stale";
  }
  return `Skill Bill: ${stepLabel} (stale)`;
}

function settledAt(updatedAt: Date | undefined, now: Date): Date {
  return updatedAt ?? now;
}
