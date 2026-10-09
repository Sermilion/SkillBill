import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { PreferenceCachePort } from "../application/PreferenceCachePort";
import { StatusRefreshCoordinator } from "../application/StatusRefreshCoordinator";
import { StatusRepository } from "../application/StatusRepository";
import { SkillBillStatusOutcome, UnavailableReason } from "../domain/SkillBillStatusOutcome";

class FakePreferences implements PreferenceCachePort {
  constructor(public refreshIntervalSeconds = 60) {}
  getCliExecutableOverride(): string | undefined {
    return undefined;
  }
  setCliExecutableOverride(): void {}
  getRefreshIntervalSeconds(): number {
    return this.refreshIntervalSeconds;
  }
  setRefreshIntervalSeconds(): void {}
  getLastKnownDisplayCache() {
    return undefined;
  }
  setLastKnownDisplayCache(): void {}
}

class FakeStatusRepository implements StatusRepository {
  callCount = 0;
  maxInFlight = 0;
  private inFlight = 0;
  private gate: Promise<void> | undefined;
  private releaseGate: (() => void) | undefined;

  constructor(private readonly handler: () => Promise<SkillBillStatusOutcome> | SkillBillStatusOutcome) {}

  armGate(): void {
    this.gate = new Promise((resolve) => {
      this.releaseGate = resolve;
    });
  }

  release(): void {
    this.releaseGate?.();
  }

  async fetchStatus(): Promise<SkillBillStatusOutcome> {
    this.callCount += 1;
    this.inFlight += 1;
    this.maxInFlight = Math.max(this.maxInFlight, this.inFlight);
    try {
      if (this.gate) {
        await this.gate;
      }
      return await this.handler();
    } finally {
      this.inFlight -= 1;
    }
  }
}

function idle(summary = "idle"): SkillBillStatusOutcome {
  return { kind: "idle", observedAt: new Date(), summary };
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe("StatusRefreshCoordinator", () => {
  it("accepts the next run after a terminal result and rejects resurrection after a failed poll", async () => {
    const metadata = {
      repositoryIdentity: "repo",
      branchCorrelation: "main",
      executionScope: "standalone_phase" as const,
      statusStoreId: "store",
      executionId: "run-1",
      runSequence: "1",
      statusRevision: "2",
      invocationId: "invocation-1",
      phaseId: "review",
    };
    const observedAt = new Date("2026-10-09T10:00:00Z");
    const active: SkillBillStatusOutcome = {
      ...metadata, kind: "active", summary: "running", observedAt,
      currentStepId: "review", currentStepLabel: "Review", updatedAt: observedAt,
    };
    const responses: SkillBillStatusOutcome[] = [
      { ...metadata, kind: "done", summary: "finished", observedAt },
      { kind: "unavailable", summary: "poll failed", observedAt, reasonCode: UnavailableReason.PROCESS_FAILURE },
      active,
      { ...active, executionId: "run-2", runSequence: "2", statusRevision: "1", invocationId: "invocation-2" },
    ];
    const repo = new FakeStatusRepository(() => responses.shift()!);
    const coordinator = new StatusRefreshCoordinator(repo, new FakePreferences(), "/tmp/a");
    const seen: SkillBillStatusOutcome[] = [];
    const completed = new Promise<void>((resolve) => {
      coordinator.subscribe((outcome) => {
        seen.push(outcome);
        if (outcome.executionId === "run-2") resolve();
      });
    });
    try {
      for (let index = 0; index < 4; index += 1) coordinator.requestRefresh();
      await completed;
      assert.equal(seen.some((outcome) => outcome.kind === "active" && outcome.executionId === "run-1"), false);
      assert.equal(seen.at(-1)?.kind, "active");
    } finally {
      coordinator.dispose();
    }
  });

  it("coalesces overlapping refresh requests", async () => {
    const repo = new FakeStatusRepository(() => idle());
    repo.armGate();
    const prefs = new FakePreferences(60);
    const coordinator = new StatusRefreshCoordinator(repo, prefs, "/tmp/a");
    coordinator.addConsumer();
    await delay(30);
    coordinator.requestRefresh();
    await delay(30);
    assert.equal(repo.maxInFlight, 1);
    repo.release();
    await delay(50);
    coordinator.dispose();
  });

  it("stops polling after dispose", async () => {
    let cancelled = false;
    const repo = new FakeStatusRepository(async () => {
      await delay(10_000);
      return idle();
    });
    const prefs = new FakePreferences(1);
    const coordinator = new StatusRefreshCoordinator(repo, prefs, "/tmp/b", () => {
      cancelled = true;
    });
    coordinator.addConsumer();
    await delay(50);
    const calls = repo.callCount;
    coordinator.dispose();
    assert.equal(cancelled, true);
    await delay(200);
    assert.equal(repo.callCount, calls);
  });
});
