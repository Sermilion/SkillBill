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

const CORRELATED_METADATA = {
  repositoryIdentity: "repo",
  branchCorrelation: "main",
  executionScope: "standalone_phase" as const,
  statusStoreId: "store",
  statusRevision: "2",
  invocationId: "invocation-1",
  phaseId: "review",
};

const OBSERVED_AT = new Date("2026-10-09T10:00:00Z");

function doneSnapshot(executionId: string, runSequence: string): SkillBillStatusOutcome {
  return {
    ...CORRELATED_METADATA,
    kind: "done",
    summary: "finished",
    observedAt: OBSERVED_AT,
    executionId,
    runSequence,
  };
}

function activeSnapshot(executionId: string, runSequence: string | undefined): SkillBillStatusOutcome {
  return {
    ...CORRELATED_METADATA,
    kind: "active",
    summary: "running",
    observedAt: OBSERVED_AT,
    currentStepId: "review",
    currentStepLabel: "Review",
    updatedAt: OBSERVED_AT,
    executionId,
    runSequence,
  };
}

async function acceptedOutcome(responses: SkillBillStatusOutcome[]): Promise<SkillBillStatusOutcome[]> {
  const remaining = [...responses];
  const repo = new FakeStatusRepository(() => remaining.shift()!);
  const coordinator = new StatusRefreshCoordinator(repo, new FakePreferences(), "/tmp/a");
  const seen: SkillBillStatusOutcome[] = [];
  coordinator.subscribe((outcome) => {
    seen.push(outcome);
  });
  try {
    for (let index = 0; index < responses.length; index += 1) {
      coordinator.requestRefresh();
      const target = index + 1;
      const deadline = Date.now() + 2_000;
      while (repo.callCount < target && Date.now() < deadline) {
        await delay(10);
      }
      await delay(20);
    }
    return seen;
  } finally {
    coordinator.dispose();
  }
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

  it("accepts a lower-sequence live snapshot over a displayed done snapshot", async () => {
    const seen = await acceptedOutcome([
      doneSnapshot("run-1", "11"),
      activeSnapshot("run-2", "10"),
    ]);
    const last = seen.at(-1);
    assert.equal(last?.kind, "active");
    assert.equal(last?.executionId, "run-2");
    assert.equal(last?.runSequence, "10");
    if (last?.kind === "active") {
      assert.equal(last.repositoryIdentity, "repo");
      assert.equal(last.branchCorrelation, "main");
    }
  });

  it("accepts a live snapshot with no run sequence over a displayed done snapshot", async () => {
    const seen = await acceptedOutcome([
      doneSnapshot("run-1", "11"),
      activeSnapshot("run-2", undefined),
    ]);
    assert.equal(seen.at(-1)?.kind, "active");
    assert.equal(seen.at(-1)?.executionId, "run-2");
    assert.equal(seen.at(-1)?.runSequence, undefined);
  });

  it("rejects an older live snapshot when a newer live snapshot is displayed", async () => {
    const seen = await acceptedOutcome([
      activeSnapshot("run-1", "11"),
      activeSnapshot("run-2", "10"),
    ]);
    assert.equal(seen.at(-1)?.kind, "active");
    assert.equal(seen.at(-1)?.executionId, "run-1");
    assert.equal(seen.at(-1)?.runSequence, "11");
  });

  it("accepts a later live parent goal monitor after a held child implement snapshot", async () => {
    const child: SkillBillStatusOutcome = {
      ...CORRELATED_METADATA,
      executionScope: "workflow",
      statusStoreId: "child-store",
      kind: "active",
      summary: "implementing",
      observedAt: OBSERVED_AT,
      issueKey: "0AC-46",
      workflowId: "w-child",
      workflowFamily: "feature-task",
      currentStepId: "implement",
      currentStepLabel: "Implement",
      updatedAt: OBSERVED_AT,
      executionId: "child-execution",
      runSequence: "21",
      statusRevision: "1",
      invocationId: undefined,
      phaseId: undefined,
    };
    const goal: SkillBillStatusOutcome = {
      ...child,
      statusStoreId: "goal-store",
      summary: "monitoring",
      workflowId: "goal-1",
      workflowFamily: "feature-goal",
      currentStepId: "monitor",
      currentStepLabel: "Monitor",
      executionId: "goal-execution",
      runSequence: "19",
    };
    const seen = await acceptedOutcome([child, goal]);
    const last = seen.at(-1);
    assert.equal(last?.kind, "active");
    assert.equal(last?.executionId, "goal-execution");
    if (last?.kind === "active") {
      assert.equal(last.currentStepId, "monitor");
    }
  });

  it("accepts a live branch workflow after a finished standalone phase snapshot", async () => {
    const standalone: SkillBillStatusOutcome = {
      ...CORRELATED_METADATA,
      kind: "blocked",
      summary: "blocked",
      observedAt: OBSERVED_AT,
      issueKey: "0AC-46",
      currentStepId: "validation",
      currentStepLabel: "Validation",
      updatedAt: OBSERVED_AT,
      executionId: "validation-execution",
      runSequence: "11",
      statusStoreId: "standalone-store",
      phaseId: "validation",
    };
    const workflow: SkillBillStatusOutcome = {
      ...CORRELATED_METADATA,
      executionScope: "workflow",
      statusStoreId: "goal-store",
      kind: "active",
      summary: "monitoring",
      observedAt: OBSERVED_AT,
      issueKey: "0AC-46",
      workflowId: "goal-1",
      workflowFamily: "feature-goal",
      currentStepId: "monitor",
      currentStepLabel: "Monitor",
      updatedAt: OBSERVED_AT,
      executionId: "goal-execution",
      runSequence: "2",
      statusRevision: "1",
      invocationId: undefined,
      phaseId: undefined,
    };
    const seen = await acceptedOutcome([standalone, workflow]);
    const last = seen.at(-1);
    assert.equal(last?.kind, "active");
    assert.equal(last?.executionId, "goal-execution");
    if (last?.kind === "active") {
      assert.equal(last.currentStepId, "monitor");
    }
  });

  it("rejects resurrection of the same execution from done to active", async () => {
    const seen = await acceptedOutcome([
      doneSnapshot("run-1", "11"),
      activeSnapshot("run-1", "11"),
    ]);
    assert.equal(seen.at(-1)?.kind, "done");
    assert.equal(seen.at(-1)?.executionId, "run-1");
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
