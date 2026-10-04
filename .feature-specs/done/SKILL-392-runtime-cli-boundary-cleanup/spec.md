# SKILL-392 - runtime-cli-boundary-cleanup

## Mode

decomposed

## Intended Outcome

runtime-cli becomes a thin inbound adapter:
- The feature-task run use case, with its workflow open, single execution-identity derivation and worker lease, moves into runtime-engine.
- CLI commands own their collaborators privately instead of passing them in argument data classes.
- The CLI's cycle and area-isolation guards enforce the rules ARCHITECTURE.md documents.
- Duplicated command bodies, settlement builders, option tokens and status literals collapse to one owner each.

CLI stdout, stderr, exit codes, JSON payloads, help text and command order stay byte-identical.

## Overview

[investigation.md](investigation.md) records the baseline (`ae23f4f28`), the measured census, all 13 checklist answers, findings F-001 to F-012, feasibility, what stays unchanged and sibling coordination.

Two subtasks, each one commit:

1. `spec_subtask_1_cli-composition-and-guard-integrity.md` covers F-002 to F-010. It changes runtime-cli main, two repoTest guards and one ARCHITECTURE.md sentence. It restructures code inside the module and keeps output byte-identical.
2. `spec_subtask_2_engine-owned-feature-task-run.md` covers F-001, F-011 and F-012. It changes the engine, ports, infra and core, and it changes behavior on the path every goal subtask launches through.

Reason for the split: subtask 2 moves the lease and identity logic across modules on the goal-child launch path and must be reviewed for behavior on its own. Subtask 1 is a large set of intra-module moves, including the package relocations that break three cycles, and would bury that semantic diff. Subtask 2 depends on subtask 1 because both edit `FeatureTaskRuntimeRunPreparation`, `FeatureTaskRuntimeRunExecution` and `CliRunInputs`.

## Acceptance Criteria

1. Subtask 1's acceptance criteria hold (F-002 to F-010).
2. Subtask 2's acceptance criteria hold (F-001, F-011, F-012).
3. No new module, framework, dependency bag, parameter object carrying collaborators, inbound use-case interface, architecture-test class or baseline entry is added. The only new production types are the engine feature-task run entry, its input model and one CLI lookup-status vocabulary.
4. CLI stdout, stderr, exit codes, JSON key names and order, help text and subcommand registration order match `ae23f4f28` for every existing command, as evidenced by the existing CLI and runtime-mcp parity suites passing unchanged.

## Constraints

- Follow runtime-kotlin/ARCHITECTURE.md Design Principles and docs/code-principles.md: no `//` comments in Kotlin, KDoc only on interfaces, wire keys through owning `*Keys` objects, and package sibling limits (12 per production package, 20 per model package).
- Keep the retention decisions listed in investigation.md "What stays unchanged": run preparation in the CLI, direct driven-port use, `CliRuntimeContext` and `OptionalCallbacks` seams, the deprecated alias, and the runtime-mcp parity tests.
- Extend existing scanners only where a criterion requires enforcement (subtask 1, AC 2, 4 and 5). Keep every baseline empty or shrinking.
- Do not edit the SKILL-387, SKILL-388, SKILL-389, SKILL-390, SKILL-391, SKILL-393, SKILL-395, SKILL-396 or SKILL-397 bundles.

## Non-Goals

- Moving run preparation or resume verification out of the CLI.
- Typing the CLI's own run-report statuses, changing any wire token or JSON shape, or bumping a contract version.
- Replacing `IllegalArgumentException` reporting in domain, application or infra (a recorded follow-up).
- Engine-internal cleanups outside the run entry: `trim().uppercase()` sites, `FeatureTaskRuntimeRunner` getters, and preflight add-on resolution.
- Moving the 52 `skillbill.cli` integration tests, splitting files by size, adopting `explicitApi`, or narrowing visibility wholesale.

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli) **(this bundle)**
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

This bundle's preparation ran no build or test. Evidence is static source, a Python and grep census, and Git state. After writing, `skill-bill goal preflight SKILL-392` (read-only) validates the manifest.

For implementation:
- **Build** runs the selected pack's compile gate. It proves kotlin-inject resolution of the new engine entry, the new CLI classes and the parent-provided `RepositoryEnclosingRootPort`, plus the new detekt constructor counts.
- **Validate** runs the full project checks. These include `:runtime-core:repoTest`, where the extended `InjectConstructorDefaultsArchitectureTest`, the exact-SCC runtime-cli census in `ApplicationPackageAcyclicityArchitectureTest`, `RuntimeCliAreaIsolationArchitectureTest` and `RuntimeEngineInboundApiTest` live. They also include the runtime-cli and runtime-mcp suites, with `CliRuntimeShellCommandsTest`, `CliAuthoringParityTest`, the goal CLI tests and `McpSystemToolsTest` as the byte-identity evidence, and the engine worker-coordinator and admission suites as identity-derivation evidence.

Reviewers compare subcommand registration order and help output against `ae23f4f28`.

## Next Path

skill-bill goal SKILL-392
