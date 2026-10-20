# SKILL-419 subtask 1: QA contracts, pack capability, and standalone `phase qa`

Parent spec: `spec.md`

## Scope

This subtask lands everything the two later integrations consume, plus the first working entry point.

- A canonical QA receipt contract: `../../../orchestration/contracts/qa-receipt-schema.yaml` (Draft 2020-12, `contract_version` "0.1"), a Kotlin `*_CONTRACT_VERSION` constant, a `QaReceiptPayloadKeys` object in runtime-contracts as the single owner of its wire keys, and typed failure codes (for example `INVALID_QA_RECEIPT_SCHEMA`) in `FeatureTaskRuntimeFailureCode` with matching `SkillBillRuntimeException` subclasses.
- Receipt fields: verdict `passed | findings_open | skipped | incomplete`; repository checkpoint and uncommitted tree identity; built artifact identity; policy; target (platform, redacted or hashed device identity, `physical | simulator`, selection `explicit | single | substituted`); tool versions where obtainable; exercised and untested flows with reasons and a hardware-only flag; assertions mapped to acceptance criteria when available; findings (id, affected criteria, steps, expected, observed, evidence references); `skip_reason` enum (`capability_absent`, `unsupported_target`, `disabled_policy`, `isolation_unavailable`, `ambiguous_target`, `unauthorized_target`, `offline_target`, `device_contended`, `empty_scope`); `incomplete_reason` enum (`interrupted`, `automation_broken`, `budget_exhausted`, `device_lost`, `other`); bounded evidence references, never bodies; cleanup outcome with retained resource identities and guidance.
- One shared receipt validator in the engine that every consumer calls. Beyond schema shape it enforces the semantic rules of AC-010: `passed` requires at least one exercised flow with at least one assertion and no untested selected flow; the receipt's tree identity must equal the identity the caller expects; `skipped` must carry a `skip_reason`; `incomplete` must carry an `incomplete_reason`; a simulator target cannot mark a hardware-only flow as exercised. Malformed output loud-fails with the typed failure code and never degrades to a skip.
- A pack-declared QA capability. `platform-pack-schema.yaml` gains an optional, `x-runtime-anchored` `qa_capability` object (platforms `android | ios`, a pack-relative guidance file, plus whatever discovery hints the guidance needs). The `PlatformManifest` model and `ShellContentLoaderPackBuild` loader read it and throw `InvalidManifestSchemaError` on malformed shapes. A `QaCapabilityResolver`, modeled on `ValidationGateResolver`, returns Declared, Absent, or Incompatible through dominant-pack routing with no slug branching. Absent becomes an `unsupported_target` skip with a reason; Incompatible or malformed fails loudly.
- Pack content: `../../../platform-packs/kmp` gets Android ADB QA guidance and `platform-packs/ios/` gets iOS guidance covering project XCUITest reuse, Appium XCUITest driver with WebDriverAgent on a physical device, `devicectl` for device and app lifecycle, simulator fallback and Maestro reuse, prerequisite discovery (macOS host, Xcode, pairing, Developer Mode, signing, runner readiness) with an actionable skip for each, and the rule that simulator runs never claim hardware coverage. Android guidance covers authorized, unauthorized, offline, absent, and ambiguous device states, explicit or single-device selection, artifact build or location for the tested revision, install, lifecycle, input, screenshots, UI hierarchy, filtered logs, and existing UI automation reuse. Both are pack-owned `.md` files declared in each manifest, not listed skills.
- A device lease port in runtime-ports with an infra adapter: lease records under `.git/skill-bill/qa/` keyed by device identity, carrying owner token, pid liveness, and invocation id. A live foreign owner yields `device_contended`. A stale owner is reclaimed through the same live/stale checks the standalone monitor ownership uses. Release happens on success, skip, failure, and cancellation; a release failure emits a record and lands in the receipt's cleanup section.
- Standalone `skill-bill phase qa [<instructions>] --agent <agent>`: a `SkeletonDefinition.QA` (IN_MEMORY, `standaloneInvocable = true`) whose `stepIds` contain only `qa` (no `qa_fix`), the `PhaseSlot.QA("qa", listOf(qa, qa_fix))` slot placed between AUDIT and CODE_REVIEW, the `qa`/`qa_fix` ids in `FeatureTaskRuntimePhaseIds`, and a QA strategy registered in `RuntimeFeatureTaskSlotProvides`. The standalone run compares a tracked-file and HEAD baseline before and after (mirroring verify's worktree baseline) and fails on any change. Generated scripts and evidence go under `.git/skill-bill/qa/<invocation>/`. A valid receipt with findings exits 0; a malformed or missing receipt exits 1. Standalone phase status and `ide-status-schema.yaml` gain a QA outcome that distinguishes attempted, passed, findings, skipped, and incomplete.
- `../../../skills/skill-bill/content.md` gains a `phase:qa` routing row, frontmatter description mention, and a "Phase QA" section. `docs/runtime-command-guidance.md` lists `qa` among in-memory standalone phases. `PhaseCommand`'s description text names qa.

Placing `PhaseSlot.QA` in the enum here does not add it to `FEATURE_RUN_SLOTS`; that change and its resume handling belong to subtask 2.

## Acceptance Criteria

1. `../../../orchestration/contracts/qa-receipt-schema.yaml` exists with the verdict, identity, target, flow, assertion, finding, skip reason, incomplete reason, evidence reference, and cleanup fields listed in Scope, and a matching Kotlin contract version constant, a `QaReceiptPayloadKeys` keys object, and typed QA failure codes exist in runtime-contracts.
2. A single engine receipt validator applies the schema plus the semantic rules from Scope (no `passed` without exercised and asserted flows, tree identity must match the expected identity, reasons required for `skipped` and `incomplete`, simulator cannot exercise a hardware-only flow), and both the standalone QA path and the QA strategy call it.
3. `platform-pack-schema.yaml`, `PlatformManifest`, and the pack loader support an optional `qa_capability` declaration, and a malformed declaration raises `InvalidManifestSchemaError`.
4. `QaCapabilityResolver` resolves QA support from manifest declarations without branching on pack slugs, maps an absent declaration to an `unsupported_target` skip with a reason, and fails loudly on an incompatible declaration.
5. The kmp pack declares Android QA guidance and the ios pack declares iOS QA guidance with the device-state, selection, artifact, simulator, and prerequisite content described in Scope, and neither adds a listed skill.
6. A device lease port and infra adapter own per-device leases under `.git/skill-bill/qa/`, return `device_contended` for a live foreign owner, reclaim stale owners through live/stale checks, and record cleanup failures with retained resource identities.
7. `skill-bill phase qa` is a standalone-invocable in-memory phase whose step set excludes `qa_fix`, keeps generated scripts and evidence under `.git/skill-bill/qa/<invocation>/`, fails when tracked files or HEAD change, exits 0 for any valid receipt including findings, and exits 1 for malformed or missing output.
8. Standalone phase status and `ide-status-schema.yaml` distinguish attempted, passed, findings, skipped, and incomplete QA, and every fallback, target substitution, retry, and cleanup failure in the standalone path emits a record that carries no credentials, raw device serials, or device log bodies.
9. `../../../skills/skill-bill/content.md` and `docs/runtime-command-guidance.md` document `/skill-bill [<instructions>] phase:qa` mapping to `skill-bill phase qa [<instructions>] --agent <agent>`, and `/skill-bill` stays the only listed skill.

## Non-goals

- Feature-task workflow integration, `qa_fix`, `qa:auto|off`, and pre-QA resume (subtask 2).
- Verify integration (subtask 3).
- A bespoke device automation server, automatic SDK or Appium installation, or committed harness code.
- Runtime-side device discovery or UI interaction. The agent discovers and drives devices from pack guidance; the runtime owns only capability resolution, the device lease, revision binding, and receipt validation.

## Dependency Notes

None. Subtasks 2 and 3 consume the receipt contract, validator, capability resolver, device lease, and QA strategy defined here.

Assumption for implement to confirm: whether the pack loader pins a single platform-pack `contract_version`. If adding an optional field can keep "1.8" valid, keep it. If the loader pins one constant, bump to "1.9" and update every pack `platform.yaml` in this same commit.

## Test Obligations

- Receipt schema accept and reject parity through the real validators: one accepted receipt per verdict, and a rejected receipt for missing reason, unknown skip reason, and evidence body instead of reference (catches schema and Kotlin drift).
- Semantic validator: a `passed` receipt with zero assertions, a receipt whose tree identity differs from the expected one, and a simulator receipt that marks a hardware-only flow exercised are each rejected (AC-010).
- `QaCapabilityResolver`: absent declaration yields `unsupported_target`, malformed manifest `qa_capability` raises `InvalidManifestSchemaError` (AC-011).
- Device lease: a second live owner gets `device_contended` and the first owner's record survives unchanged; a stale owner is reclaimed (AC-014).
- Standalone phase: a run whose agent edits a tracked file fails with the worktree-changed error; a findings receipt exits 0 and a malformed receipt exits 1 (AC-005).
- Tool-dependent smoke tests run only where adb or a macOS host exists and record an explicit skip otherwise; fakes do not count as device proof.

## Validation Strategy

Validate runs the repository's required maintainer checks (`./gradlew check` and the agent-config and skill validators the validation gate declares), including the architecture tests for wire vocabulary ownership, comment rules, and production file line ceilings. Implement and audit run nothing.

## Implementation Details

Paths are abbreviated. `runtime-domain/...` means `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/...`, and the same for the other runtime modules. Each task lists the criteria it serves. Where the preplan digest did not establish a fact, the step records an assumption that implement confirms when it opens the file.

### Task 1. Phase ids, slot, and projection ids (AC 7)

- `runtime-domain/.../workflow/taskruntime/model/core/FeatureTaskRuntimePhaseIds.kt`: add `QA = "qa"` and `QA_FIX = "qa_fix"` and include both in `all`.
- `runtime-domain/.../workflow/taskruntime/model/skeleton/PhaseSlot.kt`: add `QA("qa", listOf(FeatureTaskRuntimePhaseIds.QA, FeatureTaskRuntimePhaseIds.QA_FIX))` between `AUDIT` and `CODE_REVIEW`. Ascending ordinal order is required by `SkeletonDefinition`'s init. Do not touch `FEATURE_RUN_SLOTS`, `GOAL_CHILD`, `STANDALONE`, or any `semanticRevision`. Subtask 2 owns those.
- `runtime-domain/.../phase/task/FeatureTaskRuntimePhaseWorkflowDefinition.kt`: add `PHASE_QA`, `PHASE_QA_FIX`, and `PhaseProjectionContract.QA_RECEIPT = "feature_task_runtime.qa_receipt"`. Leave `QA_FIX_LOOP_ID` and `MAX_QA_FIX_ATTEMPTS` to subtask 2.
- Leave `FeatureTaskRuntimePhaseWorkflowGraph` and `FeatureTaskRuntimePhaseWorkflowTransitions` alone. Their step ids come from `FEATURE_RUN_SLOTS`, which this subtask does not change. Assumption to confirm: no exhaustive `when` over `PhaseSlot` or check over `FeatureTaskRuntimePhaseIds.all` requires a graph label for every id. If one exists, add the minimal entry it needs, such as an IDE status label, and do not add a graph step.

### Task 2. QA receipt contract, keys, and failure codes (AC 1)

- New `../../../orchestration/contracts/qa-receipt-schema.yaml`, Draft 2020-12, `contract_version` const `"0.1"`, `additionalProperties: false` at every object level. Follow `feature-task-runtime-build-receipt.yaml` for layout. Fields:
  - `verdict` enum `passed | findings_open | skipped | incomplete`.
  - `identity`: `head_revision` (40-hex) and `tree_identity`, the uncommitted tree identity in the shape `feature-task-runtime-checkpoint-identity-schema.yaml` already defines. Reuse that shape through a `$ref` if the contract loader resolves cross-file refs. Otherwise duplicate the field pattern. Implement confirms which.
  - `artifact`: `{ kind, path_ref, sha256 }`. Nullable only when the verdict is `skipped`.
  - `policy` enum `standalone | auto | on | off`. `standalone` is used here. Subtasks 2 and 3 use the others.
  - `target`: `platform` (`android | ios`), `device_fingerprint` (a sha256 hex pattern, never a raw serial), `kind` (`physical | simulator`), `selection` (`explicit | single | substituted`). Nullable when the verdict is `skipped` and no device was chosen.
  - `tool_versions`: a map of string to bounded string (maxLength 64).
  - `flows`: an array of `{ id, name, status: exercised | untested, hardware_only: boolean, untested_reason? }`. `untested_reason` is required when the status is `untested`.
  - `assertions`: an array of `{ flow_id, description, outcome: pass | fail, criteria: [string] }`.
  - `findings`: an array of `{ id, affected_criteria: [string], steps: [string], expected, observed, evidence_refs }`.
  - `skip_reason` and `incomplete_reason` enums with exactly the values listed in Scope.
  - `evidence_refs`: an array, maxItems 32, of `{ kind: screenshot | ui_hierarchy | log_excerpt | recording | script, path, sha256 }`. `path` must match a pattern rooted at `.git/skill-bill/qa/` with maxLength 512. No body, content, or data property exists, so an inline body fails `additionalProperties`.
  - `fallbacks`: an array of `{ kind: simulator_substitution | target_substitution | retry, reason }`, with `reason` maxLength 200. This is the receipt's channel for the observability records in Task 9. It is an addition to the Scope field list and serves AC 8.
  - `cleanup`: `{ outcome: released | retained, retained_resources: [{ kind, identity }], guidance? }`. `guidance` is required when the outcome is `retained`.
  - Schema-level conditionals with `if`/`then`: `skipped` requires `skip_reason`, `incomplete` requires `incomplete_reason`, and neither reason may appear under any other verdict.
- New `runtime-contracts/.../contracts/qa/QaReceiptContract.kt`, holding `QA_RECEIPT_CONTRACT_VERSION = "0.1"` and the schema resource path. Also add `QaReceiptPayloadKeys`, the single owner of every wire key above. The package name is an assumption. Place it next to wherever the build-receipt contract constant lives if that location is more consistent.
- `runtime-contracts/.../error/shellcontent/FeatureTaskRuntimeFailureCode.kt`: add `INVALID_QA_RECEIPT_SCHEMA`, `QA_RECEIPT_IDENTITY_MISMATCH`, `QA_RECEIPT_UNSUPPORTED_CLAIM` (semantic-rule violations), `QA_RECEIPT_MISSING`, `INCOMPATIBLE_QA_CAPABILITY`, `QA_DEVICE_LEASE_FAILED`, and `QA_WORKTREE_CHANGED`. Add matching `SkillBillRuntimeException` subclasses under `skillbill.error.featuretask.*`. Assumption: if verify's `WORKTREE_CHANGED` code lives in a shared enum that a phase run can raise, reuse it and drop `QA_WORKTREE_CHANGED`.
- Register `QaReceiptPayloadKeys` wherever the wire-vocabulary architecture inventory requires (`WireVocabularyGovernedSeamInventory.kt`) if that test demands it for new key owners.

### Task 3. Shared receipt validator (AC 2)

- New `runtime-engine/.../featuretask/slot/qa/receipt/QaReceiptValidator.kt` plus a typed `QaReceipt` model in a sibling file. The package path is an assumption. Verify (subtask 3) also calls this validator, so keep it free of feature-task run-loop types. Signature: `validate(rawJson: String, expected: QaExpectedIdentity): QaReceipt`, where `QaExpectedIdentity` holds the head revision and tree identity.
- Order: parse and validate shape with the repository's existing schema-validation helper (the one the build receipt uses), then check the contract version, then apply the semantic rules. Each failure throws the typed exception from Task 2. A failure never maps to a skip.
- Semantic rules:
  - `passed` needs at least one `exercised` flow that has at least one `pass` assertion, zero `untested` flows, and zero findings.
  - `findings_open` needs at least one finding.
  - The receipt identity must equal the expected identity, otherwise `QA_RECEIPT_IDENTITY_MISMATCH`. Under a `skipped` verdict with no artifact, only the head revision is compared.
  - A `simulator` target with an `exercised` flow where `hardware_only = true` is rejected with `QA_RECEIPT_UNSUPPORTED_CLAIM`.
  - A finding's evidence ref paths must sit under the invocation directory the caller passes in. This blocks screenshots from another run.
- Add a `skipped(reason, detail, expected)` builder that runtime paths use to synthesize skip receipts. Those receipts go through `validate` as well, so the runtime has one encode/validate path.

### Task 4. Pack schema, manifest model, and loader (AC 3)

- `../../../orchestration/contracts/platform-pack-schema.yaml`: add an optional top-level `qa_capability` with `x-runtime-anchored: true` and `additionalProperties: false`. Fields: `platforms` (non-empty unique array of `android | ios`), `guidance_file` (a pack-relative `.md` path pattern), and an optional `discovery_hints` array of bounded strings. Add an `x-coherence-checks` rule that `guidance_file` must appear in `declared_files`. Assumption: `declared_files` accepts arbitrary pack-relative paths. Version: keep `"1.8"` if the loader accepts the optional field without a const change. If the loader pins one constant, bump to `"1.9"` and update every pack `platform.yaml` in the same commit, as the Dependency Notes say.
- `runtime-domain/.../scaffold/model/ScaffoldModels.kt`: add `qaCapability: QaCapabilityDeclaration?` to `PlatformManifest`, plus a `QaCapabilityDeclaration(platforms: Set<QaPlatform>, guidanceFile: String, discoveryHints: List<String>)` and a `QaPlatform` enum with `fromWire`.
- `runtime-infra/skills/.../platformpack/loader/ShellContentLoaderPackBuild.kt`: in `buildPack`, parse `qa_capability`. A missing key becomes null. A wrong type, unknown platform, missing guidance file on disk, or path escaping the pack throws `InvalidManifestSchemaError`, as `validation_gate` does today.

### Task 5. `QaCapabilityResolver` (AC 4)

- New `runtime-engine/.../featuretask/slot/qa/capability/QaCapabilityResolver.kt` and `QaCapabilityResolution`, modeled on `validation/ValidationGateResolver.kt` and `ValidationGateCycleModels.kt`. `resolve(changedPaths)` uses the same dominant-pack routing. Results:
  - `Declared(packSlug, declaration, guidancePath)`.
  - `Absent(routedPackSlug?)`. A null slug means no pack routed.
  - `Incompatible(reason)`, for example a guidance file that disappeared after load.
- No `when` or `if` on a slug literal. Callers map `Absent` to `QaReceiptValidator.skipped(unsupported_target, "no installed pack declares qa_capability for <slug or 'this repository'>")`. They map `Incompatible` to a thrown `INCOMPATIBLE_QA_CAPABILITY`.
- Standalone input: the changed paths are the repository's tracked files, or the routing set the resolver's sibling uses for a whole-repo scope. Assumption: `ValidationGateResolver` has a whole-repo entry point to reuse. Otherwise pass the tracked-file list from `git ls-files`.

### Task 6. Pack QA guidance (AC 5)

- New `../../../platform-packs/kmp/qa/android-qa.md` and `platform-packs/ios/qa/ios-qa.md`. Add each to its pack's `declared_files` and `qa_capability` (`platforms: [android]` for kmp, `[ios]` for ios). Assumption: the agent-config validator accepts a declared non-skill `.md` outside `addons/`. If it requires governed `.md` files under known roots, move them to whichever root it accepts, without making them skills or add-ons.
- Android content:
  - Classify `adb devices -l` output into authorized, `unauthorized`, `offline`, absent, and ambiguous (more than one authorized device with no explicit selection). Map each to its `skip_reason`.
  - Explicit or single-device selection, and the hashing rule for `device_fingerprint`.
  - Build or locate the artifact for the tested revision and record its sha256.
  - `adb install -r`, launch, input, `screencap`, `uiautomator dump`, and `logcat` filtered by the app pid, keeping only excerpts written as evidence refs.
  - Reuse of existing instrumentation or Maestro flows.
  - Release the lease through the Task 7 command.
- iOS content:
  - Prerequisite discovery: macOS host, Xcode and `xcrun`, `devicectl list devices` pairing, Developer Mode, signing identity and team, and WebDriverAgent runner readiness. Each gets an actionable skip with a fix-it line.
  - Reuse of project XCUITest schemes.
  - Appium XCUITest driver with WebDriverAgent on a physical device.
  - `devicectl` app install, launch, and terminate.
  - Simulator fallback and Maestro reuse, with `target.kind: simulator` plus a `fallbacks` entry. A simulator never marks a `hardware_only` flow exercised.
- Both files share these rules: write scripts and evidence only under the invocation directory, never edit tracked files, commit, push, or open PRs, never paste log bodies or raw serials into the receipt, and write the receipt to the path the briefing names.

### Task 7. Device lease port and adapter (AC 6, AC 8)

- New `runtime-ports/.../qa/QaDeviceLeasePort.kt`, an interface with KDoc. `acquire(deviceFingerprint, invocationId): QaLeaseResult` returns `Acquired(lease)` or `Contended(ownerInvocationId)`. `release(lease): QaLeaseReleaseOutcome` returns `Released` or `Failed(retainedIdentity, detail)`. `releaseAllFor(invocationId)` exists for cancellation and end-of-run cleanup.
- New infra adapter in `runtime-infra`: one file per device at `.git/skill-bill/qa/leases/<fingerprint>.json` holding owner token, pid, process start marker, invocation id, and acquired-at. Create it atomically (temp file plus atomic move, or create-new). On an existing file, run the same live/stale checks `standalone-monitor-ownership` uses. Live and foreign means `Contended`, and the file is not touched. Stale means reclaim, emitting a record. If those checks are private to the monitor adapter, extract them into a shared internal helper in runtime-infra without changing monitor behavior. Implement confirms where they live.
- Durable record: per AC-012 the lease file is a new durable QA record. Add `../../../orchestration/contracts/qa-device-lease-schema.yaml` (contract `"0.1"`), a Kotlin version constant and `QaDeviceLeasePayloadKeys` in runtime-contracts, and validate the file on read. A malformed lease file throws `QA_DEVICE_LEASE_FAILED`. It is never silently overwritten.
- Agent access: the runtime does no device discovery, so the agent needs a way to take a lease before installing. Add a small CLI subcommand, `skill-bill qa lease acquire|release --invocation <id> --device <serial>`. It hashes the serial before any storage or output, prints `acquired` or `contended`, and exits 0 for both. The guidance tells the agent to call it, and a `contended` result becomes a `device_contended` skip. This command is the decision this plan settled. Implement may move it under `skill-bill phase qa` if the CLI structure suits that better.
- Bind the adapter in the existing composition root that wires port adapters (`RuntimeFeatureTaskSlotProvides` or its sibling DI module). No second root.

### Task 8. QA strategy, skeleton, and standalone run (AC 2, AC 7)

- New `runtime-engine/.../featuretask/slot/qa/QaStrategy.kt` (slot `QA`, steps `[qa]`, entry `qa`, `executionBindingKind = AGENT`). Assumption: `PhaseStrategyRegistry` accepts a strategy whose steps are a subset of its slot. If it requires the full slot, declare `qa_fix` as an optional step whose `runStep` throws a typed "qa_fix not composed" error, and leave real behavior to subtask 2.
- Prompt sections in `QaPromptSections.kt` carry:
  - the resolved guidance file path;
  - the invocation directory;
  - the expected head and tree identity;
  - the receipt path `.git/skill-bill/qa/<invocation>/receipt.json`;
  - the lease command;
  - the report-only rules;
  - the instruction that an app launch alone is never a pass.
- `stepHooks` settlement reads the receipt file and calls `QaReceiptValidator.validate`. Register the strategy in `runtime-core/.../di/featuretask/RuntimeFeatureTaskSlotProvides.kt` and in the test fixtures `PhaseStrategyRegistryFixtures.kt` and `TestPhaseStrategies.kt`.
- `SkeletonDefinition.kt`: add `QA = SkeletonDefinition("qa", listOf(PhaseSlot.QA), runStateKind = IN_MEMORY, standaloneInvocable = true, stepIds = listOf(FeatureTaskRuntimePhaseIds.QA))` to `entries`. `PhaseInvocationParser.phaseNames()` then lists `qa` automatically.
- New `runtime-engine/.../featuretask/phaserun/QaStandalonePhaseRun.kt`, called from `PhaseRunEntry.run` when the definition is QA. Keep `PhaseRunEntry` under its line ceiling. Sequence:
  1. Mint an invocation id and create `.git/skill-bill/qa/<invocation>/`.
  2. Capture the baseline: HEAD plus tracked-file status. Reuse or extract verify's `worktreeBaseline` into a shared engine helper rather than copying it.
  3. Compute the expected identity.
  4. Resolve capability. On `Absent`, synthesize the `unsupported_target` skip and do not launch the agent.
  5. Run the step through the existing phase traversal.
  6. Validate the receipt.
  7. In a `finally`, call `releaseAllFor(invocationId)`, which also covers cancellation. A release failure is folded into the receipt's `cleanup` with `retained` and emits a record.
  8. Recompare the baseline. A change throws the worktree-changed failure even when the receipt is valid.
- Result mapping: any validated receipt, `findings_open` included, becomes `Completed`, which exits 0. A missing receipt (`QA_RECEIPT_MISSING`), malformed receipt, identity mismatch, or baseline change becomes `Blocked`, which exits 1.
- The CLI report prints the verdict, exercised and untested flows, findings, any skip or incomplete reason, and cleanup. It never prints evidence bodies.
- `runtime-cli/.../cli/phase/PhaseCommand.kt`: add qa to the in-memory list in the `DocumentedCliCommand` description, for example "Review, validation, qa, pr and monitor keep no workflow state".

### Task 9. Status, IDE schema, and observability (AC 8)

- `runtime-ports/.../idestatus/model/StandalonePhaseStatusModels.kt`: add a `QaOutcome` enum (`ATTEMPTED`, `PASSED`, `FINDINGS`, `SKIPPED`, `INCOMPLETE`) and a nullable `qaOutcome` on the standalone status update. Publish `ATTEMPTED` at launch and the receipt-derived outcome on settle through `StandalonePhaseStatusPublisherFactory`.
- `../../../orchestration/contracts/ide-status-schema.yaml`: add the matching optional enum property. If the schema pins a contract version, bump it along with its Kotlin constant. Update the projector mapping (`IdeStatusProjectorMapping.kt`) and its keys object.
- After validation the runtime emits one observability record per `fallbacks` entry (simulator substitution, target substitution, retry), one per stale-lease reclaim, and one per cleanup failure. Use the existing event sink and record shape from `../../../docs/observability-policy.md`. Records carry the fingerprint, kind, and bounded reason only.

### Task 10. Dispatcher and docs (AC 9)

- `../../../skills/skill-bill/content.md`:
  - Mention `phase:qa` in the frontmatter `description`.
  - Add a Forms and Routing row: `/skill-bill [<instructions>] phase:qa` → `skill-bill phase qa [<instructions>] --agent <currently-executing-agent>`.
  - Add a "Phase QA" section next to "Phase Review". It covers report-only behavior, exit codes, the receipt location, and the fact that platform is resolved by packs, not by the dispatcher.
  - Edit `content.md` only, never generated `SKILL.md`.
- `../../../docs/runtime-command-guidance.md`: add `qa` to the in-memory standalone phases paragraph and document the `skill-bill qa lease` command.
- Check `../../../README.md`'s catalog and add `phase:qa` there if it lists phases.
- Add no new listed skill.

### Tests to add (validate runs them)

1. Receipt schema parity (Task 2), one parameterized test through the real validator: accept one receipt per verdict, and reject a skipped receipt with no reason, an unknown `skip_reason`, and an evidence ref with an inline body. Bug caught: schema and `QaReceiptPayloadKeys` drift.
2. Semantic validator (Task 3): reject `passed` with zero assertions, a mismatched tree identity, and a simulator receipt with an exercised hardware-only flow. Bug caught: a false QA success claim (AC-010).
3. Capability (Tasks 4 and 5): an absent declaration resolves to an `unsupported_target` skip, and a malformed `qa_capability` in a fixture manifest raises `InvalidManifestSchemaError`.
4. Device lease (Task 7), with a temp `.git` dir: a second live owner gets `Contended` and the first file is byte-identical afterward, and a stale-pid record is reclaimed.
5. Standalone phase (Task 8), with a fake agent: one that edits a tracked file fails with the worktree-changed error, a `findings_open` receipt exits 0, and a malformed receipt exits 1.
6. Tool-dependent smoke: the runtime never calls adb or xcrun, so no runtime test can prove device support. Add no fake-device test. Real smoke runs happen only on hosts with adb or macOS, and anywhere else the run records an explicit skip. This host is Linux, so iOS smoke is a recorded skip.

These need no separate tests: the phase id and slot constants, prompt text, the CLI description, the docs, and the status enum plumbing. The schema parity test and existing architecture tests cover them.

### Constraints

- No `//` or block comments in runtime-kotlin. KDoc on interfaces only.
- Wire keys are declared once in `*Keys` objects.
- Respect the production file line ceilings. New logic goes in new files and does not grow `PhaseRunEntry`, `VerifyOperation`, or `VerifyStepSequence`.
- One composition root. No slug branching anywhere in QA code.
- Never put credentials, raw serials, or log bodies in receipts, records, or telemetry.
- Implement does not build, test, or run check. Validate owns all of that. No installer or install-sync steps are part of this plan.
- Out of scope: `FEATURE_RUN_SLOTS`, `qa_fix` behavior, `qa:auto|off`, the execution-plan schema, and verify. Subtasks 2 and 3 own them.

## Next Path

```bash
skill-bill goal SKILL-419
```
