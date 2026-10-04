# SKILL-393 - runtime-ports-ownership-and-guard-carve-outs

## Mode

decomposed

## Intended Outcome

runtime-ports holds only contracts that cross a module boundary. The goal-runner store contracts and ide-status request/result models that only runtime-engine implements and consumes move into runtime-engine. Engine then re-exports nothing from ports through aliases. Repository-driving decomposition and goal-parent behaviour leaves ports for application or engine. The four guard carve-outs that admitted it are deleted, and the ports declaration guard gains a rule that fails if such behaviour returns. Behaviour, persisted bytes, and CLI/MCP wire output stay the same. No module, framework, dependency bag, or architecture-test class is added, and no baseline grows.

## Overview

[investigation.md](investigation.md) holds the census, the SKILL-377 landing check, the principle and checklist tables, the guard coverage matrix, the over-engineering register, what stays, coordination, and limits. Summary:

| Finding | Priority | Current state | Delivery |
| --- | --- | --- | --- |
| F-001 | High | 56 ports declarations (goal-runner stores, their roles and models, 4 ide-status models) are implemented and consumed only by runtime-engine. Engine re-exports 36 ports types through public typealiases and pins alias names. CLI imports `GoalRunnerRepairResult` under both names. Ports `IdeStatusCandidate` duplicates engine's. | Subtask 1 |
| F-002 | High | SKILL-372 moved `GoalParentProjectionWriter`, decomposition manifest discovery, parent discovery, projection-failure persistence, and 7 application-only DTOs into ports, adding four carve-outs: two file exemptions, a raw-map FQN allow-list entry, and a `persistence|workflow` cycle baseline row. | Subtask 2 |
| F-003 | Medium | `DecompositionManifestProjectionWriter` has one application implementation, 0 substitutes, and only engine consumers. | Subtask 2 |
| F-004 | Medium | 12 forwarding `validateX(payload: Any, …)` extensions on `FeatureTaskRuntimeWireArtifactValidator`, which SKILL-372 planned to delete. | Subtask 2 |
| F-005 | Medium | `PortsDeclarationArchitectureTest` has no rule for repository-driving top-level functions, so SKILL-233's cleanup regressed. | Subtask 2 |
| F-006 | Low | `ReviewAttributionPort.composedLaunchPlan` is a constant default that only a test fixture relies on. | Subtask 2 |
| F-007 | Low | `REVIEW_EVIDENCE_BATCH_SIZE` has two owners, and the installer output cap and sentinel are host-adapter policy declared in ports. | Subtask 2 |
| F-008 | Low | The companion-`NONE` census scans only runtime-engine, though SKILL-377 subtask 3 asked for ports too. | Subtask 2 |

Why two subtasks: F-001 is a mechanical move. It touches about 115 files, mostly engine imports, and changes no body. The semantic work is relocating behaviour into application, deleting an interface and the forwarders, and editing four guards. Putting it in a separate commit keeps that diff reviewable. Subtask 2 runs second, so the extended declaration guard goes live on a tree that no longer needs its exemption. Each commit builds and stands alone.

Baseline: commit `ae23f4f28f16d851a0548e8149e0fe6fadbbc612`. Ports main at baseline: 251 files, 7,516 lines, 553 top-level declarations.

Next command: `skill-bill goal SKILL-393`.

## Acceptance Criteria

1. runtime-ports main declares none of the 56 declarations listed in investigation F-001. Runtime-engine declares each of them, and its persistence, repair, and ide-status request/result models are declared under the names the engine aliases use today.
2. runtime-engine main declares no typealias whose target is a `skillbill.ports.*` type. runtime-ports main declares no `IdeStatusCandidate`. No runtime-cli main file imports a type both from runtime-ports and from runtime-engine under the same simple name.
3. Every entry in `RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES` names an engine declaration, not an alias, and the inbound API test passes with CLI reading the moved repair and reset results.
4. runtime-ports main declares no top-level non-DTO class, no public enum outside a `model` package, no `WorkflowStateRepository` extension function, and no top-level function taking `UnitOfWork`, `WorkflowEngine`, `DecompositionManifestStore`, or `GoalRunnerPersistenceSession`. `GoalParentProjectionWriter` is not public in any module.
5. `PortsDeclarationArchitectureTest` has no file exemption, `RuntimeLayerBoundaryArchitectureTest`'s model-package rule exempts no runtime-ports path, and `rawMapBoundaryAccessors` names no `skillbill.ports.*` declaration. `runtime-ports-package-cycle-baseline.txt` is empty, and every guard passes.
6. `PortsDeclarationArchitectureTest` rejects a synthetic top-level ports function with a `*Repository` receiver, and one with a `UnitOfWork` or `*Store` parameter. It accepts a synthetic derived extension that calls only its receiver's own members.
7. `DecompositionManifestProjectionWriter` does not exist, and the three engine classes that used it receive `DecompositionManifestWriter`.
8. `FeatureTaskRuntimeWireArtifactValidatorExtensions.kt` does not exist, and no public runtime-ports function declares a parameter of type `Any`.
9. `ReviewAttributionPort.composedLaunchPlan` has no default body. runtime-ports main declares neither `REVIEW_EVIDENCE_BATCH_SIZE` nor the installer output cap and truncation sentinel, and each value has one declaration in its adapter module.
10. The companion-`NONE` census case of `PortNullObjectAbsenceArchitectureTest` reads runtime-ports main as well as runtime-engine main, and it passes.
11. Decomposition manifest discovery, parent discovery, projection-failure persistence, goal-parent projection, and review preparation are byte-identical to baseline. That covers returned values, persisted rows, `error(...)` messages, the governed review evidence `maxItems`, and the installer truncation text. CLI and MCP wire output is unchanged.
12. `../../../runtime-kotlin/ARCHITECTURE.md` describes the landed ports surface and the new declaration rule. `runtime-kotlin/agent/decisions.md` records that 2026-09-06 (b) is superseded for `LoadedDecompositionManifest` and `ValidatedDecompositionManifestYaml`, with the SQLite-no-longer-reads evidence.

## Constraints

- Run on the current tree, and do not wait for another issue. If a sibling bundle has already changed a named file, apply this bundle's criteria to what is present.
- No module, framework, dependency bag, parameter object, or architecture-test class. The only scanner changes are F-005's rule in `PortsDeclarationArchitectureTest` and F-008's root list in `PortNullObjectAbsenceArchitectureTest`. Grow no baseline, pinned-type list excepted: the pinned list names the four moved CLI-rendered repair results and drops two alias-only entries.
- Keep every interface that has a test substitute or a cross-module consumer. Keep the role splits and the SKILL-377 and SKILL-358 retention decisions listed under What stays.
- Follow `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles and `docs/code-principles.md`: no `//` comments in Kotlin, KDoc only on interfaces, wire keys through their `*Keys` owners, and the package sibling limits.
- CLI and MCP wire output, persisted bytes, schemas, contract versions, and transaction extents stay unchanged.
- Test doubles live in testFixtures and compose with `by` delegation. Name the regression each changed test catches.

## Non-Goals

- Changing the `WorkflowStateRecord` row shape, or moving `toSnapshot`/`toRecord`.
- Renaming `*Port`-suffixed interfaces after they move.
- Collapsing role interfaces, or deleting store interfaces that have test substitutes.
- The eleven domain entries in `rawMapBoundaryAccessors` (domain owner).
- `DiffResolverPort` (SKILL-388), the `HostPlatformPort` java-command member (SKILL-392), and the `RuntimeComponent` store accessors (SKILL-389).
- Switching the ports package-cycle census to exact-package mode, `explicitApi()`, or blanket visibility narrowing.

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports) **(this bundle)**
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Build proves compilation and kotlin-inject resolution through the dominant pack's build command. Validate runs these suites:

- the runtime-ports, runtime-engine, runtime-application, runtime-cli, runtime-core, and runtime-infra workflow, launcher, host, contracts, and sqlite suites;
- `:runtime-core:repoTest` (architecture);
- the routed pack quality gate.

The regressions to catch:

- parent discovery choosing a different parent, or losing its ambiguity error;
- manifest discovery including an archived bundle;
- projection-failure persistence leaving the artifact behind;
- a goal-runner store resolving the wrong binding after the move;
- an ide-status projection changing when read through the engine types;
- review evidence batches accepting more than 32 items.

Existing suites cover each of these. Preparation ran no build or test.

## Next Path

`skill-bill goal SKILL-393`
