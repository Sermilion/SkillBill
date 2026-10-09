import { SkillBillStatusOutcome, StatusDiagnostic } from "./SkillBillStatusOutcome";

export interface CachedDisplaySnapshot {
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
  activeDurationMs?: number;
  activeDurationAsOf?: Date;
  subtaskActiveDurationMs?: number;
  subtaskActiveDurationAsOf?: Date;
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

export const MAX_SUMMARY_CHARS = 512;

export interface LastKnownDisplayCache {
  display: CachedDisplaySnapshot;
  observedAt: Date;
}

export function toCacheSnapshotOrNull(outcome: SkillBillStatusOutcome): LastKnownDisplayCache | null {
  switch (outcome.kind) {
    case "active":
      return {
        display: liveSnapshot(outcome),
        observedAt: outcome.observedAt,
      };
    case "paused":
      return {
        display: liveSnapshot(outcome),
        observedAt: outcome.observedAt,
      };
    case "stale":
      if (outcome.fromCache) {
        return null;
      }
      return {
        display: staleSnapshot(outcome),
        observedAt: outcome.observedAt,
      };
    case "blocked":
    case "failed":
      return {
        display: blockedFailedSnapshot(outcome),
        observedAt: outcome.observedAt,
      };
    case "done":
      return {
        display: {
          summary: outcome.summary,
          repositoryIdentity: outcome.repositoryIdentity,
          issueKey: outcome.issueKey,
          progressCompleted: outcome.progressCompleted,
          progressTotal: outcome.progressTotal,
          startedAt: outcome.startedAt,
          updatedAt: outcome.updatedAt,
          activeDurationMs: outcome.activeDurationMs,
          activeDurationAsOf: outcome.activeDurationAsOf,
          subtaskActiveDurationMs: outcome.subtaskActiveDurationMs,
          subtaskActiveDurationAsOf: outcome.subtaskActiveDurationAsOf,
          executionScope: outcome.executionScope,
          executionId: outcome.executionId,
          statusStoreId: outcome.statusStoreId,
          branchCorrelation: outcome.branchCorrelation,
          runSequence: outcome.runSequence,
          statusRevision: outcome.statusRevision,
          invocationId: outcome.invocationId,
          phaseId: outcome.phaseId,
          currentActivity: outcome.currentActivity,
        },
        observedAt: outcome.observedAt,
      };
    default:
      return null;
  }
}

function liveSnapshot(
  outcome: Extract<SkillBillStatusOutcome, { kind: "active" | "paused" }>,
): CachedDisplaySnapshot {
  return {
    summary: outcome.summary,
    repositoryIdentity: outcome.repositoryIdentity,
    issueKey: outcome.issueKey,
    currentStepId: outcome.currentStepId,
    currentStepLabel: outcome.currentStepLabel,
    progressCompleted: outcome.progressCompleted,
    progressTotal: outcome.progressTotal,
    startedAt: outcome.startedAt,
    currentSubtaskId: outcome.currentSubtaskId,
    subtaskStartedAt: outcome.subtaskStartedAt,
    updatedAt: outcome.updatedAt,
    activeDurationMs: outcome.activeDurationMs,
    activeDurationAsOf: outcome.activeDurationAsOf,
    subtaskActiveDurationMs: outcome.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf: outcome.subtaskActiveDurationAsOf,
    executionScope: outcome.executionScope,
    executionId: outcome.executionId,
    statusStoreId: outcome.statusStoreId,
    branchCorrelation: outcome.branchCorrelation,
    runSequence: outcome.runSequence,
    statusRevision: outcome.statusRevision,
    invocationId: outcome.invocationId,
    phaseId: outcome.phaseId,
    currentActivity: outcome.currentActivity,
  };
}

function staleSnapshot(outcome: Extract<SkillBillStatusOutcome, { kind: "stale" }>): CachedDisplaySnapshot {
  return {
    summary: outcome.summary,
    repositoryIdentity: outcome.repositoryIdentity,
    issueKey: outcome.issueKey,
    currentStepId: outcome.currentStepId,
    currentStepLabel: outcome.currentStepLabel,
    progressCompleted: outcome.progressCompleted,
    progressTotal: outcome.progressTotal,
    startedAt: outcome.startedAt,
    currentSubtaskId: outcome.currentSubtaskId,
    subtaskStartedAt: outcome.subtaskStartedAt,
    updatedAt: outcome.updatedAt,
    activeDurationMs: outcome.activeDurationMs,
    activeDurationAsOf: outcome.activeDurationAsOf,
    subtaskActiveDurationMs: outcome.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf: outcome.subtaskActiveDurationAsOf,
    executionScope: outcome.executionScope,
    executionId: outcome.executionId,
    statusStoreId: outcome.statusStoreId,
    branchCorrelation: outcome.branchCorrelation,
    runSequence: outcome.runSequence,
    statusRevision: outcome.statusRevision,
    invocationId: outcome.invocationId,
    phaseId: outcome.phaseId,
    currentActivity: outcome.currentActivity,
  };
}

function blockedFailedSnapshot(
  outcome: Extract<SkillBillStatusOutcome, { kind: "blocked" | "failed" }>,
): CachedDisplaySnapshot {
  return {
    summary: outcome.summary,
    repositoryIdentity: outcome.repositoryIdentity,
    issueKey: outcome.issueKey,
    currentStepId: outcome.currentStepId,
    currentStepLabel: outcome.currentStepLabel,
    startedAt: outcome.startedAt,
    currentSubtaskId: outcome.currentSubtaskId,
    subtaskStartedAt: outcome.subtaskStartedAt,
    updatedAt: outcome.updatedAt,
    activeDurationMs: outcome.activeDurationMs,
    activeDurationAsOf: outcome.activeDurationAsOf,
    subtaskActiveDurationMs: outcome.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf: outcome.subtaskActiveDurationAsOf,
    executionScope: outcome.executionScope,
    executionId: outcome.executionId,
    statusStoreId: outcome.statusStoreId,
    branchCorrelation: outcome.branchCorrelation,
    runSequence: outcome.runSequence,
    statusRevision: outcome.statusRevision,
    invocationId: outcome.invocationId,
    phaseId: outcome.phaseId,
    currentActivity: outcome.currentActivity,
  };
}

export function toStaleOutcome(cache: LastKnownDisplayCache): SkillBillStatusOutcome {
  const display = cache.display;
  return {
    kind: "stale",
    observedAt: cache.observedAt,
    summary: display.summary,
    repositoryIdentity: display.repositoryIdentity,
    issueKey: display.issueKey,
    currentStepId: display.currentStepId,
    currentStepLabel: display.currentStepLabel,
    progressCompleted: display.progressCompleted,
    progressTotal: display.progressTotal,
    startedAt: display.startedAt,
    currentSubtaskId: display.currentSubtaskId,
    subtaskStartedAt: display.subtaskStartedAt,
    updatedAt: display.updatedAt,
    activeDurationMs: display.activeDurationMs,
    activeDurationAsOf: display.activeDurationAsOf,
    subtaskActiveDurationMs: display.subtaskActiveDurationMs,
    subtaskActiveDurationAsOf: display.subtaskActiveDurationAsOf,
    executionScope: display.executionScope,
    executionId: display.executionId,
    statusStoreId: display.statusStoreId,
    branchCorrelation: display.branchCorrelation,
    runSequence: display.runSequence,
    statusRevision: display.statusRevision,
    invocationId: display.invocationId,
    phaseId: display.phaseId,
    currentActivity: display.currentActivity,
    fromCache: true,
    diagnostic: { reasonCode: "cache_fallback" } satisfies StatusDiagnostic,
  };
}
