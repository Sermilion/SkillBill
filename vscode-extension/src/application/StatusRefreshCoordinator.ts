import { PreferenceCachePort } from "./PreferenceCachePort";
import { StatusRepository } from "./StatusRepository";
import { LastKnownDisplayCache, toCacheSnapshotOrNull, toStaleOutcome } from "../domain/LastKnownDisplayCache";
import { UNCORROBORATED_IDLE_TOLERANCE } from "../domain/Constants";
import {
  isLiveOutcome,
  isPollTransportFailure,
  isUncorroboratedIdle,
  SkillBillStatusOutcome,
  UnavailableReason,
  withPollFailure,
} from "../domain/SkillBillStatusOutcome";

export type OutcomeListener = (outcome: SkillBillStatusOutcome) => void;

export class StatusRefreshCoordinator {
  private activeConsumers = 0;
  private disposed = false;
  private refreshChain: Promise<void> = Promise.resolve();
  private unconfirmedIdleSamples = 0;
  private currentOutcome: SkillBillStatusOutcome | undefined;
  private refreshGeneration = 0;
  private acceptedOutcome: SkillBillStatusOutcome | undefined;
  private pollTimer: ReturnType<typeof setInterval> | undefined;
  private readonly listeners = new Set<OutcomeListener>();

  constructor(
    private readonly statusRepository: StatusRepository,
    private readonly preferences: PreferenceCachePort,
    private readonly projectRoot: string,
    private readonly onCancelProcesses: () => void = () => undefined,
  ) {}

  subscribe(listener: OutcomeListener): () => void {
    this.listeners.add(listener);
    if (this.currentOutcome) {
      listener(this.currentOutcome);
    }
    return () => this.listeners.delete(listener);
  }

  addConsumer(): void {
    if (this.disposed) {
      return;
    }
    this.activeConsumers += 1;
    if (this.activeConsumers === 1) {
      this.startPolling();
    }
  }

  removeConsumer(): void {
    this.activeConsumers = Math.max(0, this.activeConsumers - 1);
    if (this.activeConsumers === 0) {
      this.stopPolling();
    }
  }

  requestRefresh(): void {
    if (this.disposed) {
      return;
    }
    void this.refreshOnce();
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    this.refreshGeneration += 1;
    this.activeConsumers = 0;
    this.stopPolling();
    this.onCancelProcesses();
    this.listeners.clear();
  }

  private startPolling(): void {
    if (this.pollTimer) {
      return;
    }
    void this.refreshOnce();
    this.scheduleNextPoll();
  }

  private scheduleNextPoll(): void {
    const intervalMs = Math.max(1, this.preferences.getRefreshIntervalSeconds()) * 1000;
    this.pollTimer = setInterval(() => {
      if (this.disposed || this.activeConsumers <= 0) {
        this.stopPolling();
        return;
      }
      void this.refreshOnce();
    }, intervalMs);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  private refreshOnce(): Promise<void> {
    this.refreshChain = this.refreshChain.then(() => this.runRefresh());
    return this.refreshChain;
  }

  private async runRefresh(): Promise<void> {
    if (this.disposed) {
      return;
    }
    const requestGeneration = this.refreshGeneration;
    let outcome: SkillBillStatusOutcome;
    try {
      outcome = await this.statusRepository.fetchStatus(this.projectRoot);
    } catch {
      if (this.disposed || requestGeneration !== this.refreshGeneration) {
        return;
      }
      const fallback = this.transportFailureFallback(UnavailableReason.PROCESS_FAILURE);
      if (fallback) {
        this.emit(fallback);
      }
      return;
    }

    if (this.disposed || requestGeneration !== this.refreshGeneration) {
      return;
    }

    if (hasLegacyExecutionCorrelation(outcome)) {
      const legacy = this.currentCorrelationMatchesCache(this.preferences.getLastKnownDisplayCache());
      if (legacy) this.emit(toStaleOutcome(legacy));
      return;
    }

    if (isSuccessfulSnapshot(outcome) && this.correlationChanged(outcome)) {
      this.refreshGeneration += 1;
      this.currentOutcome = undefined;
      this.acceptedOutcome = undefined;
      this.preferences.setLastKnownDisplayCache(undefined);
      this.unconfirmedIdleSamples = 0;
    }

    if (isStoreReplacement(this.orderingOutcome(), outcome)) {
      this.refreshGeneration += 1;
      this.currentOutcome = undefined;
      this.acceptedOutcome = undefined;
      this.preferences.setLastKnownDisplayCache(undefined);
      this.unconfirmedIdleSamples = 0;
    }

    if (isUncorroboratedIdle(outcome)) {
      const held = this.currentOutcome;
      if (held && isLiveOutcome(held)) {
        this.unconfirmedIdleSamples += 1;
        if (this.unconfirmedIdleSamples <= UNCORROBORATED_IDLE_TOLERANCE) {
          this.emit(held);
          return;
        }
      }
    } else {
      this.unconfirmedIdleSamples = 0;
    }

    if (outcome.kind === "unavailable" && isPollTransportFailure(outcome.reasonCode)) {
      const fallback = this.transportFailureFallback(outcome.reasonCode);
      if (fallback) {
        this.emit(fallback);
        return;
      }
    }

    if (!acceptsNewerStatus(this.orderingOutcome(), outcome)) {
      const cache = this.preferences.getLastKnownDisplayCache();
      if (!this.currentOutcome && cache) this.emit(toStaleOutcome(cache));
      return;
    }

    if (isSuccessfulSnapshot(outcome)) {
      this.acceptedOutcome = outcome;
    }

    let toEmit: SkillBillStatusOutcome;
    if (outcome.kind === "unavailable" && isPollTransportFailure(outcome.reasonCode)) {
      toEmit = this.transportFailureFallback(outcome.reasonCode) ?? outcome;
    } else if (outcome.kind === "unavailable" || outcome.kind === "incompatible") {
      const cache = this.currentCorrelationMatchesCache(this.preferences.getLastKnownDisplayCache());
      toEmit = cache ? toStaleOutcome(cache) : outcome;
    } else {
      const snapshot = toCacheSnapshotOrNull(outcome);
      if (snapshot) {
        this.preferences.setLastKnownDisplayCache(snapshot);
      }
      toEmit = outcome;
    }
    this.emit(toEmit);
  }

  private orderingOutcome(): SkillBillStatusOutcome | undefined {
    if (this.acceptedOutcome) return this.acceptedOutcome;
    const cache = this.preferences.getLastKnownDisplayCache();
    return cache ? toStaleOutcome(cache) : this.currentOutcome;
  }

  private transportFailureFallback(reason: UnavailableReason): SkillBillStatusOutcome | undefined {
    const held = this.currentOutcome;
    if (held && isLiveOutcome(held)) {
      return withPollFailure(held, reason);
    }
    const cache = this.currentCorrelationMatchesCache(this.preferences.getLastKnownDisplayCache());
    return cache ? toStaleOutcome(cache) : undefined;
  }

  private currentCorrelationMatchesCache(
    cache: LastKnownDisplayCache | undefined,
  ): LastKnownDisplayCache | undefined {
    if (!cache) {
      return cache;
    }
    if (!this.currentOutcome) {
      return cache;
    }
    const current = correlationOfOutcome(this.acceptedOutcome ?? this.currentOutcome);
    const cached = correlationOfCache(cache);
    return current && cached && sameCorrelation(current, cached) ? cache : undefined;
  }

  private correlationChanged(incoming: SkillBillStatusOutcome): boolean {
    const incomingCorrelation = correlationOfOutcome(incoming);
    if (!incomingCorrelation) {
      return false;
    }
    const current = correlationOfOutcome(this.acceptedOutcome ?? this.currentOutcome);
    const cache = this.preferences.getLastKnownDisplayCache();
    const cached = correlationOfCache(cache);
    if (cache && !cached) {
      return true;
    }
    return [current, cached]
      .filter((correlation): correlation is StatusCorrelation => correlation !== undefined)
      .some((correlation) => !sameCorrelation(correlation, incomingCorrelation));
  }

  private emit(outcome: SkillBillStatusOutcome): void {
    this.currentOutcome = outcome;
    for (const listener of this.listeners) {
      listener(outcome);
    }
  }
}

interface StatusCorrelation {
  repositoryIdentity: string;
  branchCorrelation: string;
}

function correlationOfOutcome(outcome: SkillBillStatusOutcome | undefined): StatusCorrelation | undefined {
  if (!outcome || !("repositoryIdentity" in outcome) || !outcome.repositoryIdentity || !outcome.branchCorrelation) {
    return undefined;
  }
  return {
    repositoryIdentity: outcome.repositoryIdentity,
    branchCorrelation: outcome.branchCorrelation,
  };
}

function correlationOfCache(cache: LastKnownDisplayCache | undefined): StatusCorrelation | undefined {
  if (!cache?.display.repositoryIdentity || !cache.display.branchCorrelation) {
    return undefined;
  }
  return {
    repositoryIdentity: cache.display.repositoryIdentity,
    branchCorrelation: cache.display.branchCorrelation,
  };
}

function sameCorrelation(left: StatusCorrelation, right: StatusCorrelation): boolean {
  return left.repositoryIdentity === right.repositoryIdentity && left.branchCorrelation === right.branchCorrelation;
}

function isSuccessfulSnapshot(outcome: SkillBillStatusOutcome): boolean {
  return outcome.kind !== "unavailable" && outcome.kind !== "incompatible";
}

function acceptsNewerStatus(
  current: SkillBillStatusOutcome | undefined,
  incoming: SkillBillStatusOutcome,
): boolean {
  if (!current || !current.runSequence || !incoming.runSequence) {
    return true;
  }
  if (correlationOfOutcome(current)?.repositoryIdentity !== correlationOfOutcome(incoming)?.repositoryIdentity ||
      current.branchCorrelation !== incoming.branchCorrelation) {
    return false;
  }
  const sequenceOrder = compareDecimalStrings(incoming.runSequence, current.runSequence);
  if (sequenceOrder < 0) {
    return isLiveOverDone(current, incoming);
  }
  if (sequenceOrder > 0) {
    return true;
  }
  if (current.executionId !== incoming.executionId) {
    return false;
  }
  if (current.statusRevision && incoming.statusRevision &&
      compareDecimalStrings(incoming.statusRevision, current.statusRevision) < 0) {
    return false;
  }
  if (isTerminal(current.kind) && current.kind !== incoming.kind) {
    return false;
  }
  if (isTerminal(current.kind) && !isTerminal(incoming.kind)) {
    return false;
  }
  return true;
}

function isTerminal(kind: SkillBillStatusOutcome["kind"]): boolean {
  return kind === "done" || kind === "failed" || kind === "blocked";
}

function isLiveOverDone(
  current: SkillBillStatusOutcome,
  incoming: SkillBillStatusOutcome,
): boolean {
  const incomingLive =
    incoming.kind === "active" || incoming.kind === "paused" || incoming.kind === "blocked";
  if (!incomingLive) {
    return false;
  }
  if (current.executionId === incoming.executionId) {
    return false;
  }
  return isTerminal(current.kind) || !bothLiveStandalone(current, incoming);
}

function bothLiveStandalone(
  current: SkillBillStatusOutcome,
  incoming: SkillBillStatusOutcome,
): boolean {
  return isLiveStandalone(current) && isLiveStandalone(incoming);
}

function isLiveStandalone(outcome: SkillBillStatusOutcome): boolean {
  return (outcome.kind === "active" || outcome.kind === "paused") &&
    outcome.executionScope === "standalone_phase";
}

function isStoreReplacement(
  current: SkillBillStatusOutcome | undefined,
  incoming: SkillBillStatusOutcome,
): boolean {
  return Boolean(
    current?.statusStoreId &&
      incoming.statusStoreId &&
      current.statusStoreId !== incoming.statusStoreId,
  );
}

function hasLegacyExecutionCorrelation(outcome: SkillBillStatusOutcome): boolean {
  return Boolean(outcome.runSequence && !outcome.branchCorrelation);
}

function compareDecimalStrings(left: string, right: string): number {
  const normalizedLeft = left.replace(/^0+(?=\d)/, "");
  const normalizedRight = right.replace(/^0+(?=\d)/, "");
  if (normalizedLeft.length !== normalizedRight.length) {
    return normalizedLeft.length > normalizedRight.length ? 1 : -1;
  }
  if (normalizedLeft === normalizedRight) {
    return 0;
  }
  return normalizedLeft > normalizedRight ? 1 : -1;
}
