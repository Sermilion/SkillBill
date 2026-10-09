import assert from "node:assert/strict";
import { describe, it } from "mocha";
import { IDE_STATUS_CONTRACT_VERSION } from "../domain/Constants";
import { mapIdeStatusJson } from "../infrastructure/cli/IdeStatusJsonMapper";

const observedAt = new Date("2026-08-06T12:00:00Z");

describe("IdeStatusJsonMapper", () => {
  it("maps active lifecycle with planning and current phase execution", () => {
    const stdout = JSON.stringify({
      contract_version: IDE_STATUS_CONTRACT_VERSION,
      repository_identity: "repo",
      lifecycle_state: "active",
      summary: "running",
      current_step: { id: "implement", label: "Implement" },
      updated_at: "2026-08-06T11:00:00Z",
      planning: {
        state: "partially_planned",
        shared_preplan_prepared: true,
        planned_subtask_count: 2,
        total_subtask_count: 4,
      },
      current_phase_execution: {
        phase_id: "implement",
        kind: "attempt",
        count: 1,
      },
    });
    const outcome = mapIdeStatusJson(stdout, observedAt, 0);
    assert.equal(outcome.kind, "active");
    if (outcome.kind === "active") {
      assert.ok(outcome.planning);
      assert.ok(outcome.currentPhaseExecution);
      assert.equal(outcome.currentPhaseExecution?.kind, "attempt");
    }
  });

  it("preserves large standalone run identity and bounded activity", () => {
    const outcome = mapIdeStatusJson(JSON.stringify({
      contract_version: IDE_STATUS_CONTRACT_VERSION,
      repository_identity: "repo",
      workflow_id: "workflow-411",
      execution_scope: "standalone_phase",
      execution_id: "execution-411",
      status_store_id: "store-1",
      run_sequence: "90071992547409931234567890",
      status_revision: "90071992547409931234567891",
      invocation_id: "invocation-411",
      phase_id: "review",
      lifecycle_state: "active",
      summary: "review",
      current_step: { id: "review", label: "Review" },
      current_activity: "Checking findings",
      updated_at: "2026-08-06T11:00:00Z",
    }), observedAt, 0);
    assert.equal(outcome.kind, "active");
    if (outcome.kind === "active") {
      assert.equal(outcome.runSequence, "90071992547409931234567890");
      assert.equal(outcome.executionScope, "standalone_phase");
      assert.equal(outcome.phaseId, "review");
      assert.equal(outcome.currentActivity, "Checking findings");
    }
  });

  it("degrades malformed optional planning to null without failing the outcome", () => {
    const stdout = JSON.stringify({
      contract_version: IDE_STATUS_CONTRACT_VERSION,
      repository_identity: "repo",
      lifecycle_state: "active",
      summary: "running",
      current_step: { id: "implement", label: "Implement" },
      updated_at: "2026-08-06T11:00:00Z",
      planning: { state: "partially_planned", planned_subtask_count: "bad" },
      current_phase_execution: { phase_id: "implement", kind: "unknown", count: 1 },
    });
    const outcome = mapIdeStatusJson(stdout, observedAt, 0);
    assert.equal(outcome.kind, "active");
    if (outcome.kind === "active") {
      assert.equal(outcome.planning, undefined);
      assert.equal(outcome.currentPhaseExecution, undefined);
    }
  });

  it("maps pause_requested true false and absent onto active pauseRequested", () => {
    for (const [wire, expected] of [
      [true, true],
      [false, false],
      [undefined, undefined],
    ] as const) {
      const payload: Record<string, unknown> = {
        contract_version: IDE_STATUS_CONTRACT_VERSION,
        repository_identity: "repo",
        lifecycle_state: "active",
        summary: "running",
        current_step: { id: "implement", label: "Implement" },
        updated_at: "2026-08-06T11:00:00Z",
      };
      if (wire !== undefined) {
        payload.pause_requested = wire;
      }
      const outcome = mapIdeStatusJson(JSON.stringify(payload), observedAt, 0);
      assert.equal(outcome.kind, "active");
      if (outcome.kind === "active") {
        assert.equal(outcome.pauseRequested, expected);
      }
    }
  });

  it("maps lifecycle variants", () => {
    const blocked = mapIdeStatusJson(
      JSON.stringify({
        contract_version: IDE_STATUS_CONTRACT_VERSION,
        lifecycle_state: "blocked",
        summary: "blocked",
      }),
      observedAt,
      0,
    );
    assert.equal(blocked.kind, "blocked");

    const incompatible = mapIdeStatusJson(
      JSON.stringify({ contract_version: "9.9", lifecycle_state: "idle", summary: "x" }),
      observedAt,
      0,
    );
    assert.equal(incompatible.kind, "incompatible");
  });
});
