import * as vscode from "vscode";
import {
  DEFAULT_REFRESH_INTERVAL_SECONDS,
  MAX_REFRESH_INTERVAL_SECONDS,
  MIN_REFRESH_INTERVAL_SECONDS,
} from "../../domain/Constants";
import { PreferenceCachePort } from "../../application/PreferenceCachePort";
import { LastKnownDisplayCache, MAX_SUMMARY_CHARS } from "../../domain/LastKnownDisplayCache";
import { containsAbsolutePath } from "../AbsolutePathGuard";

const CLI_PATH_KEY = "skillBill.cliPath";
const REFRESH_INTERVAL_KEY = "skillBill.refreshIntervalSeconds";
const CACHE_KEY_PREFIX = "skillBill.lastKnownDisplay.";

export class VsCodePreferenceCache implements PreferenceCachePort {
  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly workspaceKey: string,
  ) {}

  getCliExecutableOverride(): string | undefined {
    const value = vscode.workspace.getConfiguration().get<string>(CLI_PATH_KEY, "").trim();
    return value || undefined;
  }

  setCliExecutableOverride(pathValue: string | undefined): void {
    void vscode.workspace
      .getConfiguration()
      .update(CLI_PATH_KEY, pathValue ?? "", vscode.ConfigurationTarget.Workspace);
  }

  getRefreshIntervalSeconds(): number {
    const raw = vscode.workspace
      .getConfiguration()
      .get<number>(REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL_SECONDS);
    return sanitizeInterval(raw);
  }

  setRefreshIntervalSeconds(seconds: number): void {
    void vscode.workspace
      .getConfiguration()
      .update(REFRESH_INTERVAL_KEY, sanitizeInterval(seconds), vscode.ConfigurationTarget.Workspace);
  }

  getLastKnownDisplayCache(): LastKnownDisplayCache | undefined {
    const raw = this.context.workspaceState.get<StoredCache>(this.cacheKey());
    if (!raw) {
      return undefined;
    }
    return deserializeCache(raw);
  }

  setLastKnownDisplayCache(cache: LastKnownDisplayCache | undefined): void {
    if (!cache) {
      void this.context.workspaceState.update(this.cacheKey(), undefined);
      return;
    }
    const sanitized = sanitizeCache(cache);
    if (!sanitized) {
      void this.context.workspaceState.update(this.cacheKey(), undefined);
      return;
    }
    void this.context.workspaceState.update(this.cacheKey(), serializeCache(sanitized));
  }

  private cacheKey(): string {
    return `${CACHE_KEY_PREFIX}${this.workspaceKey}`;
  }
}

interface StoredCache {
  observedAt: string;
  display: {
    summary: string;
    repositoryIdentity?: string;
    issueKey?: string;
    currentStepId?: string;
    currentStepLabel?: string;
    progressCompleted?: number;
    progressTotal?: number;
    startedAt?: string;
    currentSubtaskId?: string;
    subtaskStartedAt?: string;
    updatedAt?: string;
    activeDurationMs?: number;
    activeDurationAsOf?: string;
    subtaskActiveDurationMs?: number;
    subtaskActiveDurationAsOf?: string;
    executionScope?: "workflow" | "standalone_phase";
    executionId?: string;
    statusStoreId?: string;
    branchCorrelation?: string;
    runSequence?: string;
    statusRevision?: string;
    invocationId?: string;
    phaseId?: string;
    currentActivity?: string;
  };
}

function serializeCache(cache: LastKnownDisplayCache): StoredCache {
  const display = cache.display;
  return {
    observedAt: cache.observedAt.toISOString(),
    display: {
      summary: display.summary,
      repositoryIdentity: display.repositoryIdentity,
      issueKey: display.issueKey,
      currentStepId: display.currentStepId,
      currentStepLabel: display.currentStepLabel,
      progressCompleted: display.progressCompleted,
      progressTotal: display.progressTotal,
      startedAt: display.startedAt?.toISOString(),
      currentSubtaskId: display.currentSubtaskId,
      subtaskStartedAt: display.subtaskStartedAt?.toISOString(),
      updatedAt: display.updatedAt?.toISOString(),
      activeDurationMs: display.activeDurationMs,
      activeDurationAsOf: display.activeDurationAsOf?.toISOString(),
      subtaskActiveDurationMs: display.subtaskActiveDurationMs,
      subtaskActiveDurationAsOf: display.subtaskActiveDurationAsOf?.toISOString(),
      executionScope: display.executionScope,
      executionId: display.executionId,
      statusStoreId: display.statusStoreId,
      branchCorrelation: display.branchCorrelation,
      runSequence: display.runSequence,
      statusRevision: display.statusRevision,
      invocationId: display.invocationId,
      phaseId: display.phaseId,
      currentActivity: display.currentActivity,
    },
  };
}

function deserializeCache(raw: StoredCache): LastKnownDisplayCache | undefined {
  const summary = raw.display.summary?.trim();
  if (!summary || summary.length > MAX_SUMMARY_CHARS || containsAbsolutePath(summary)) {
    return undefined;
  }
  const observedAt = new Date(raw.observedAt);
  if (Number.isNaN(observedAt.getTime())) {
    return undefined;
  }
  return {
    observedAt,
    display: {
      summary,
      repositoryIdentity: raw.display.repositoryIdentity,
      issueKey: raw.display.issueKey,
      currentStepId: raw.display.currentStepId,
      currentStepLabel: raw.display.currentStepLabel,
      progressCompleted: raw.display.progressCompleted,
      progressTotal: raw.display.progressTotal,
      startedAt: parseOptionalDate(raw.display.startedAt),
      currentSubtaskId: raw.display.currentSubtaskId,
      subtaskStartedAt: parseOptionalDate(raw.display.subtaskStartedAt),
      updatedAt: parseOptionalDate(raw.display.updatedAt),
      activeDurationMs: raw.display.activeDurationMs,
      activeDurationAsOf: parseOptionalDate(raw.display.activeDurationAsOf),
      subtaskActiveDurationMs: raw.display.subtaskActiveDurationMs,
      subtaskActiveDurationAsOf: parseOptionalDate(raw.display.subtaskActiveDurationAsOf),
      executionScope: raw.display.executionScope,
      executionId: raw.display.executionId,
      statusStoreId: raw.display.statusStoreId,
      branchCorrelation: raw.display.branchCorrelation?.trim().slice(0, 256),
      runSequence: raw.display.runSequence,
      statusRevision: raw.display.statusRevision,
      invocationId: raw.display.invocationId,
      phaseId: raw.display.phaseId,
      currentActivity: raw.display.currentActivity,
    },
  };
}

function parseOptionalDate(raw: string | undefined): Date | undefined {
  if (!raw) {
    return undefined;
  }
  const parsed = new Date(raw);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

function sanitizeInterval(seconds: number): number {
  if (!Number.isFinite(seconds)) {
    return DEFAULT_REFRESH_INTERVAL_SECONDS;
  }
  return Math.min(MAX_REFRESH_INTERVAL_SECONDS, Math.max(MIN_REFRESH_INTERVAL_SECONDS, Math.trunc(seconds)));
}

function sanitizeCache(cache: LastKnownDisplayCache): LastKnownDisplayCache | undefined {
  const summary = cache.display.summary.trim();
  if (!summary || summary.length > MAX_SUMMARY_CHARS || containsAbsolutePath(summary)) {
    return undefined;
  }
  return {
    observedAt: cache.observedAt,
    display: { ...cache.display, summary },
  };
}
