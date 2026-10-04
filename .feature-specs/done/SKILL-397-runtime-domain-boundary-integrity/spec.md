# SKILL-397 - runtime-domain-boundary-integrity

## Mode

decomposed

## Intended Outcome

runtime-domain keeps its clean core (no ambient effects, no DI annotations, no java.nio or ports imports, an always-validating WorkflowEngine, a typed snapshot) and loses the guard evasions that SKILL-372 left behind: 4 baselined package SCCs, two hardcoded per-package sibling ceilings, a curated domain FQN raw-map allow-list, validator injection through a fun interface and function types, and the renamed Noop test fixture. The 33 public wrappers that erase raw maps to `Any` gain typed returns, and the pure DecompositionManifest, DecompositionSubtask, GoalRunnerControlState and GoalRunnerStopReason transitions that engine and application hold as free functions move into domain. Every change deletes a type, parameter, constant, allow-list entry, baseline row or package level. The only additions are one `Any`-typed-declaration rule in the existing inner-layer raw-map scan, one DecompositionSubtaskAction enum beside DecompositionStatus, and one shared subtask-reset function.

Evidence, census tables, findings F-001 to F-009, the over-engineering register, what stays unchanged and coordination: [investigation.md](investigation.md). Baseline sha: `ae23f4f28f16d851a0548e8149e0fe6fadbbc612`.

## Subtasks

1. `spec_subtask_1`: domain wire boundary honesty (F-004, F-005, F-006).
2. `spec_subtask_2`: domain-owned aggregate transitions (F-007).
3. `spec_subtask_3`: domain package graph repair (F-001, F-002, F-003, F-009, mechanical). Depends on subtasks 1 and 2.

Split reason: subtask 1 changes wire-boundary signatures and extends one scanner, touching engine featuretask callers and runtime-core repoTest. Subtask 2 moves aggregate state-machine rules out of engine goalrunner and application decomposition. Subtask 3 is a large mechanical package move and rename across about 12 domain packages and their importers. Kept together, the mechanical move would bury the semantic diffs of 1 and 2. It runs last so the cycle and sibling scans judge the final file set.

## Acceptance Criteria

Each subtask spec holds the detailed, checkable criteria. The feature is done when all of them hold, which means:

1. No public declaration in runtime-domain, runtime-application or runtime-ports main is typed exactly `Any`, and the inner-layer raw-map scan used by RuntimeRawMapArchitectureTest rejects one (subtask 1 AC 1-2).
2. runtime-domain declares no validator type, parameter or property, has no testFixtures source set, and no build file depends on `testFixtures(project(":runtime-domain"))` (subtask 1 AC 3-4).
3. rawMapBoundaryAccessors holds no runtime-domain entry other than the four DurableWorkflowArtifactFamily members, and ARCHITECTURE.md Boundary Rule 11 says why (subtask 1 AC 5-6).
4. The pure manifest, subtask, control-state and stop-reason transitions named in subtask 2 are declared in runtime-domain and nowhere in runtime-engine or runtime-application main, write status and action tokens through DecompositionStatus and DecompositionSubtaskAction, and share one subtask-reset function (subtask 2 AC 1-5).
5. `runtime-domain-package-cycle-baseline.txt` records no SCC row, ArchitectureScanSupport.kt has no package-name-specific sibling ceiling, and no runtime-domain package contains `persistence.task.runtime`, `handoff.envelope` or `repair.task` (subtask 3 AC 1, 5-6).
6. The ARCHITECTURE.md Package Ownership rows for runtime-domain name only packages that exist, and every runtime-domain test package matches a main package (subtask 3 AC 7).
7. Persisted artifacts and CLI/MCP output are byte-identical to the baseline: no existing wire-fixture or expected-payload assertion is edited.

## Constraints

- No new module, framework, dependency bag, architecture-test class, typealias, exemption or baseline row.
- Extend an existing scanner only where a criterion needs enforcement (subtask 1's `Any` rule).
- Persisted bytes and CLI/MCP output stay byte-identical.
- This bundle runs on the current tree and waits for no other issue. Where SKILL-387, SKILL-388, SKILL-389, SKILL-393 or SKILL-396 has landed, rebase onto it and recount sites; where not, implement against the current tree under the second-lander rules in the subtask specs and investigation.md (Coordination with concurrent bundles).

## Non-Goals

- Items listed under "What stays unchanged" in investigation.md, including: the DurableWorkflowArtifactFamily accessor signatures, enum-typing the String status and action fields of DecompositionManifest, DecompositionSubtask and CurrentSubtaskIntent, and the skillbill.model split package shared with runtime-ports.
- Moving Path-bearing or ports-dependent helpers into domain.
- Unifying the domain and engine add-on selection decoders (F-008, recorded only).
- Replacing `require` and runCatching error reporting in domain.
- Narrowing top-level visibility (SKILL-372 F-013, not verified here).
- Deleting the ports validateX forwarders (SKILL-393).

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain) **(this bundle)**
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Each subtask runs the goal gates: build, unit tests, and the runtime-core repoTest architecture suite. That suite covers RuntimeRawMapArchitectureTest, PackageSiblingCountArchitectureTest, the EXACT_PACKAGE_SCC cycle scan, and the ambient and inject-defaults scans. It must pass with the domain cycle baseline empty, no package-specific ceiling, and no domain FQN in rawMapBoundaryAccessors except the four DurableWorkflowArtifactFamily members. No existing wire-fixture or expected-payload assertion is edited, which shows the wire output is byte-identical. Test obligations cover only the high-value behaviour: the new `Any`-typed declaration rule (synthetic fixture) and the moved manifest state-machine transitions.
