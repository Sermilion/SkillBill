# Runtime Architecture Guidelines

These guidelines exist to stop the architecture of `runtime-kotlin` from regressing
between refactor rounds. They apply to every change: feature work, fixes, refactors,
spec planning, and review. They do not replace
[ARCHITECTURE.md](../runtime-kotlin/ARCHITECTURE.md), which maps the modules,
packages, and seams, or [Code Principles](code-principles.md), which records Kotlin
patterns. They add the rules that kept being broken, how each one is enforced, and
the process that keeps fixes fixed.

Each rule has an ID. Reviews, findings, and acceptance criteria cite the IDs.

## 1. Why the architecture regressed

Four refactor rounds investigated every runtime module: SKILL-239 to SKILL-248,
SKILL-347 to SKILL-362, SKILL-370 to SKILL-378, and SKILL-386 to SKILL-397. Each round
found roughly 75 to 100 new findings, even though the round before had fixed its own.
The table classifies the 294 finding titles by keyword. A finding can fall into several
classes, so the numbers show trends, not exact totals.

| Class | R1 | R2 | R3 | R4 |
| --- | ---: | ---: | ---: | ---: |
| Guard blind, vacuous, or inert | 2 | 6 | 12 | 11 |
| Fix moved code instead of removing it, regressed, or did not land | 1 | 1 | 1 | 10 |
| Escape hatch grew (baseline, allow-list, carve-out, widened rule) | 0 | 2 | 1 | 6 |
| Code in the wrong module | 4 | 8 | 12 | 10 |
| Forwarders, aliases, dead code, unearned interfaces | 3 | 9 | 17 | 16 |
| Dependency bags, locators, second composition roots | 3 | 5 | 6 | 6 |
| Wire vocabulary restated | 1 | 7 | 5 | 7 |
| Silent fallback, untyped failure, lost cancellation | 3 | 16 | 6 | 3 |
| Ambient time, environment, or process | 0 | 6 | 3 | 2 |
| Package cycles and structure | 0 | 10 | 8 | 9 |
| Test placement | 1 | 10 | 12 | 8 |

What the evidence shows:

1. **Rules with a working mechanical check stop recurring.** Silent fallbacks fell
   from 16 to 3 once `docs/observability-policy.md` and the typed-failure scans
   landed. Ambient effects fell from 6 to 2 once the ambient scans covered every
   module.
2. **Review-only rules recur every round.** Ownership, forwarders, and dependency
   bags are judged only by review, and they are the largest classes in every round.
3. **Guards rot.** The share of findings about broken guards rose each round. Guards
   scanned a missing directory (SKILL-377 F-001), resolved the wrong root (SKILL-371
   F-001), collapsed subpackages so cycles were invisible (SKILL-374 F-005, SKILL-392
   F-003), were hand-listed per module and skipped the engine (SKILL-373 F-001,
   SKILL-378 F-008), ran without declared build inputs (SKILL-373 F-002), or had
   their fixtures hollowed so they could not fail (SKILL-389 F-003).
4. **Fixes satisfied guards by widening the exceptions.** Cycles were baselined
   instead of broken (SKILL-390 F-004, SKILL-397 F-001). Behavior was parked behind
   guard carve-outs (SKILL-393 F-002). An area-isolation rule was widened past its
   documented scope (SKILL-392 F-004). A raw-map allow-list grew (SKILL-397 F-006).
5. **Acceptance criteria allowed moves instead of removals.** A criterion phrased as
   "X is not in module A" passes when X moves to module B. Dependency bags moved
   into argument data classes (SKILL-392 F-002). Forwarders moved into ports
   (SKILL-393 F-004). A test edge was moved, not removed (SKILL-396 F-001). Nobody
   verified the result against the spec after landing (SKILL-392 F-008, SKILL-374's
   `McpToolPayloadKeys` move).
6. **Parallel bundles undid each other.** SKILL-372 silently reverted two SKILL-373
   fixes (SKILL-389 F-002).
7. **Feature work bypassed the rules.** Between SKILL-374 and SKILL-391, single-owner
   errors in `runtime-contracts` grew from 84 to 122. That included a 29-class
   runtime-operations package with one reader. No architecture bundle added it; a
   feature did.
8. **Prose drifted from code.** Each round fixed `ARCHITECTURE.md` statements that
   no longer held (SKILL-239 F-007, SKILL-374 F-007, SKILL-376 F-010, SKILL-377
   F-011, SKILL-386 F-012).

The rules below target the recurring classes. The enforcement contract (section 3)
targets lessons 3 and 4. The change process (section 4) targets lessons 5 to 8.

## 2. Architecture rules

Every rule states its check. "Guard" names an existing test in
`runtime-core/src/repoTest/kotlin/skillbill/architecture/`. "Review" means a reviewer
must check it and cite the rule ID. Section 6 lists the guards still to build.

### A1. Dependencies point inward, and each module keeps its charter

Entry adapters (`runtime-cli`, `runtime-mcp`) call application use cases and the
pinned engine API. Application and engine coordinate through ports. Ports depend on
domain and contracts. Infrastructure implements ports. `runtime-core` only composes.
Every module edge is pinned in `RuntimeModuleCatalog.moduleEdgeExpectations`.

| Module | Holds | Never holds |
| --- | --- | --- |
| runtime-contracts | Declarations read by two or more production modules or exposed by a port signature: contract DTOs, shared `*Keys`, contract versions, `JsonCodec`, `SkillBillRuntimeException` and the failure codes that adapters classify by | I/O, single-owner DTOs, keys or errors, adapter vocabulary (CLI, MCP, SQLite, HTTP names) |
| runtime-domain | Pure models, aggregates, and their invariants and transitions | `java.nio`, `skillbill.ports`, serialization, I/O, validator injection |
| runtime-ports | Purpose-built interfaces and their DTOs | Behavior, top-level objects, default bodies that stand in for an implementation, vendor protocol |
| runtime-application | Use cases outside the run loop, coordinated through ports | A second composition root, dependency bags, engine-only code, vendor protocol |
| runtime-engine | The run loop, goal runner, planning, and the pinned inbound API | Collaborator bags, receiver-extension locators, aliases over port types |
| runtime-infra/* | Port implementations | Use-case policy (status derivation, coordination), composition |
| runtime-core | Kotlin-Inject wiring | Logic, accessors that nothing reads |
| runtime-cli, runtime-mcp | Parsing, validation, rendering, exit codes | Use-case orchestration, identity derivation, process or environment reads |

Check: Guard (`RuntimeGradleModuleLayeringTest`, `RuntimeCoreCompositionOnlyTest`,
`RuntimeContractModuleImportRulesTest`, `RuntimeLayerBoundaryArchitectureTest`,
`RuntimeAdapterDependencyAllowlistTest`) plus review for the "never holds" column.

### A2. Every declaration has one owner

A declaration lives in the one module that reads it. It moves to a shared module only
when two or more production modules read it, or a port signature exposes it. This
applies to failure codes too: a `RuntimeFailureCode` enum lives with the owner of its
vocabulary, and only `SkillBillRuntimeException` is shared by every module. Pure rules
about a domain aggregate live in runtime-domain, beside the aggregate, not as free
functions in application, engine, or an adapter.

Check: Review. When you add a declaration to `runtime-contracts`, `runtime-ports`, or
`runtime-domain`, name the second consumer in the change description.
Recurred as: SKILL-370 F-006 and F-012, SKILL-374 F-002, SKILL-376 F-001, SKILL-391
F-001 and F-007, SKILL-393 F-001, SKILL-396 F-005, SKILL-397 F-007.

### A3. One composition root, no dependency bags

- `runtime-core` is the only place that constructs collaborators. Nothing else
  hand-builds a collaborator the container can wire.
- An `@Inject` class keeps its constructor parameters `private`. It exposes no
  property or getter that returns a constructor collaborator.
- No data class, argument class, or per-run state object carries collaborators
  (services, ports, repositories, runners, clocks). Pass each function the specific
  collaborator it uses.
- No function takes a service as its receiver to reach that service's
  collaborators.
- A constructor that needs more than 12 collaborators has too many responsibilities.
  Split the responsibility; do not hide the collaborators in a bag.

Check: Guard (`InjectConstructorDefaultsArchitectureTest`, which applies to
application and cli today, with engine added by SKILL-390; and the cli data-class
collaborator rule from SKILL-392). Review for the other modules until section 6 is
closed.
Recurred as: SKILL-347 F-006, SKILL-370 F-001, SKILL-373 F-004 and F-006, SKILL-377
F-002, SKILL-386 F-001 and F-003, SKILL-390 F-001 and F-002, SKILL-392 F-002 and F-005.

### A4. Ports are purpose-built

- A port names the operations its consumer needs. It is not a getter for another
  object's dependencies, and it is not a generic transport.
- Vendor protocol (URLs, headers, SQL error strings such as `SQLITE_BUSY`, git
  command flags, JSON field names) stays in the adapter. The port returns typed
  results.
- No interface default body stands in for a missing implementation. No `NONE`,
  `Noop`, `Empty`, or `Unavailable` object exists in main source. A port that can be
  absent is nullable, and the caller names its fallback.
- A port that does no I/O is not a port. Delete it or make it a plain function.

Check: Guard (`PortsDeclarationArchitectureTest`, `PortNullObjectAbsenceArchitectureTest`)
plus review for vendor protocol.
Recurred as: SKILL-358 F-004, SKILL-370 F-003 and F-011, SKILL-377 F-003, F-004 and
F-012, SKILL-378 F-004, SKILL-386 F-005, SKILL-393 F-006.

### A5. Abstractions earn their place, and fixes delete

- An interface needs a second production implementation or a test substitute.
  Otherwise use the class.
- No forwarding function, forwarding class, or pass-through `typealias`. Call the
  owner.
- Code with no production caller is deleted, not kept for tests. Test substitutes
  live in `src/testFixtures`.
- A fix for a forwarder, alias, bag, or misplaced rule deletes it. Moving it to
  another module, package, or data class does not fix it.

Check: Review ("Redundant role interfaces and application forwarders" is listed as
review-only in ARCHITECTURE.md). The typealias part is mechanical once SKILL-390 and
SKILL-393 land.
Recurred as: 45 findings across all four rounds, more than any other class.

### A6. Wire vocabulary has one owner

Follow the AGENTS.md "Wire and payload keys" rule: each key is declared once in an
owning `*Keys` object, enum tokens use `wireValue`, and no seam restates a literal.
Canonical identifiers (issue keys, repository identity) have one derivation function.

Check: Guard (`WireVocabularyArchitectureTest` over the seams in
`WireVocabularyGovernedSeamInventory`) plus review outside governed seams.

### A7. Few exceptions, results for expected outcomes, never silent

An exception means a case the runtime does not expect, and throwing one is a
deliberate act to end execution. Anything the runtime does expect is returned to the
caller as a value. Every failure belongs to one of three tiers:

| Tier | Meaning | Mechanism | Caught where |
| --- | --- | --- | --- |
| 1. Defect | A broken invariant that only a code change can cause | `require`, `check`, `requireNotNull`, `error()` | Only by the top-level crash handlers |
| 2. Expected outcome | Absent, refused, or conflicting results, or invalid input the runtime anticipates | A sealed result, a nullable, or an existing outcome type, returned by the function that knows | Nowhere; the caller branches on the value |
| 3. Anticipated failure that ends the run | Corrupt durable state, malformed contract input, or I/O failure, where the only option is to stop and tell the operator | `SkillBillRuntimeException(code, message, cause)`, where `code` is an entry of an owner-declared `RuntimeFailureCode` enum | At the CLI and MCP edges, and at a boundary that degrades, which checks `code` |

- A new custom `Throwable` subclass has to earn its place. It is allowed only for a
  failure that crosses a boundary the runtime does not own and cannot be tier 3. Its
  reason goes in a dated `runtime-kotlin/agent/decisions.md` entry.
- A new contract adds an entry to its owner's code enum, not a new exception class.
- Do not throw to report an absent, refused, or conflicting outcome.
- Do not catch `IllegalArgumentException` or `IllegalStateException` to steer
  control flow. Only the top-level arms in the CLI and MCP catch them.
- Do not put error codes in `IllegalStateException`. An edge that catches it to read
  a code also catches real bugs and reports them as user errors.
- Do not branch on exception message text.
- Untrusted input is parsed into a result or a tier 3 failure, never into `error()`
  or `require`.
- No broad catch turns a failure into absence, an empty result, or a default.
  `CancellationException` always propagates. Every fallback or degradation emits a
  record through `RuntimeDiagnostics`, as `docs/observability-policy.md` requires.

Check: Guard (`TypedParseBoundaryArchitectureTest`,
`FailureCodeTotalityArchitectureTest`; SKILL-398 adds a two-sided custom-throwable
baseline, so a new declaration fails the build) plus review.
Recurred as: SKILL-398 found 231 custom throwables in main, 62% never caught by type,
while both edges discard the type and print only the message.

### A8. Ambient effects are injected

No main code reads wall-clock or monotonic time, environment variables, system
properties, the JVM process, or the home directory directly. These come from injected
`Clock`, host ports, or composition inputs. `System.nanoTime` and `ProcessHandle`
count as ambient.

Check: Guard (the ambient-clock and ambient-environment scans in
`PrincipleEnforcementInventory.moduleArchitectureScanCases`). The forms list must
include every ambient API, including `System.nanoTime`.

### A9. Packages are acyclic and readable

No package cycle at exact-package granularity. Break a cycle at its root; never
baseline it. Keep sibling limits (12 files, 20 for model packages) by grouping noun
families, not by count. No stutter (`x.y.x`) and no one-off fragment packages.

Check: Guard (`ApplicationPackageAcyclicityArchitectureTest`,
`PackageSiblingCountArchitectureTest`, `PackageClusteringArchitectureTest`).

### A10. Tests live beside the code they exercise

A test lives in the module and package of the main code it exercises. Every test
package exists in main, except names ending in `testsupport` or `testing`. A test in
an inner module never imports a module above it.

Check: Guard for runtime-core (SKILL-389) and the inner-layer import scan. Review
elsewhere until section 6 is closed.

### A11. Narrow visibility by default

Declarations are `private` or `internal` unless another module reads them. "Public"
needs a named cross-module consumer.

Check: Review.

### A12. Inner layers stay typed

Public declarations in application, domain, and ports expose no raw map, no `Any`,
and no type alias to either. There is no curated allow-list.

Check: Guard (`RuntimeRawMapArchitectureTest`). SKILL-397 extends it to reject `Any`
and reduces the allow-list to four documented members.

## 3. Enforcement contract

Guards are code with the same failure modes as the code they police. These rules keep
them honest.

- **G1. Every rule has a status.** A rule is either in
  `PrincipleEnforcementInventory.enforceableRules`, paired with the test that proves
  it, or in `reviewOnlyRules`, with the reason a scan cannot decide it. A rule with
  neither entry does not exist.
- **G2. A guard proves it reads files.** Every scan fails when its scan set is empty.
  `ArchitectureScanSupport.kotlinFilesUnder` already fails on a missing root. A
  filtered file set must also be asserted non-empty.
- **G3. A guard proves it can fail.** Each guard has a synthetic fixture that it
  rejects through its real entry point. Weakening or deleting a fixture so a guard
  passes is a regression, and review rejects it.
- **G4. Coverage comes from the module catalog.** Per-module scans come from
  `RuntimeModuleCatalog.declaredGradleModules` through
  `PrincipleEnforcementInventory.moduleArchitectureScanCases`. Never hand-list modules
  in a guard. A new module is covered without editing the guard.
- **G5. Scans see what the compiler sees.** Parse from column 0, resolve aliased
  imports and qualified names, and use exact-package granularity for graphs. Each
  evasion seen before (alias import, inline FQN, `Any` erasure, subpackage collapse)
  has a fixture.
- **G6. The build cannot skip a guard.** The repoTest task declares every file a
  scanner reads as an input. A scanner that reads a new root adds that root to the
  task inputs in the same change.
- **G7. Exceptions only shrink.**
  - Baselines are ceilings. They may shrink, and any baseline that is empty stays
    empty by rule.
  - An exemption is a named entry on `PrincipleEnforcementInventory` with a dated
    `runtime-kotlin/agent/decisions.md` entry.
  - Inner layers get no allow-lists or carve-outs.
  - Widening what a guard accepts needs a decision entry. A change that adds a
    baseline row, allow-list entry, carve-out, or detekt suppression to make a build
    pass is rejected.

## 4. Change process

- **P1. Every diff gets the architecture check.** Feature work causes most drift, so
  the review of every runtime-kotlin diff runs the section 5 checklist, not only
  architecture bundles. The reviewer cites rule IDs for violations.
- **P2. Acceptance criteria state end states.**
  - For a relocation: "X is declared only in `<module>/<package>`, and no file
    outside `<owner>` references X."
  - For a deletion: "X exists in no source set."
  - Never "X is not in module A".
  - Each criterion names the guard that enforces it after landing, or states that it
    is review-only.
- **P3. Delete, don't move.** A finding about a forwarder, alias, bag, or carve-out is
  fixed only by deleting it. If the deletion does not compile, the finding records
  why. It is not moved to wherever it compiles.
- **P4. Verify after landing.** After a goal completes, run
  `skill-bill operation verify spec:<spec path>` against the merged result. The next
  investigation of a module starts by re-checking the previous bundle's criteria and
  retention decisions, criterion by criterion.
- **P5. Parallel bundles do not undo each other.**
  - A bundle runs on the current tree and waits for no other issue.
  - On a shared file, whichever bundle lands second applies its edit to the text
    present and keeps the other bundle's change.
  - A bundle never reverts another bundle's guard, fixture, or test edit without a
    decision entry.
  - Before review, re-run the landed criteria of every bundle that touched the same
    files.
- **P6. Plans are launchable.** A spec bundle targets the active fix branch named in
  AGENTS.md, uses repo-relative spec paths, rechecks its issue key right before
  writing, and states its parent acceptance criteria as product outcomes, not as the
  planning instructions.
- **P7. Code and tests define, prose describes.** A statement in `ARCHITECTURE.md`
  that names a count, path, or exemption is either checked by a test or removed.
  Superseding a rule or a retention decision needs a dated entry in
  `runtime-kotlin/agent/decisions.md`.
- **P8. Investigate on evidence, not on a schedule.** Run a full module investigation
  only when a quick census shows a P1 or P2 finding or a guard defect. Batch P3
  hygiene into one cross-module change, or drop it.

## 5. Review checklist

Copy this into a review of any runtime-kotlin diff and answer each line with "clean"
or a rule ID and location.

- [ ] A1: every new import points inward, and no module gained something its charter forbids.
- [ ] A2: every new shared declaration names its second consumer, and no aggregate rule landed outside domain.
- [ ] A3: no new bag, getter, receiver locator, or hand-built collaborator; `@Inject` parameters are private.
- [ ] A4: new ports are purpose-built, with no vendor protocol, default body, or null object.
- [ ] A5: no new forwarder, alias, unearned interface, or test-only production code, and fixes deleted rather than moved.
- [ ] A6: no restated key, token, or identifier derivation.
- [ ] A7: no new custom throwable without a decision entry, no expected outcome thrown, no `IllegalArgumentException` or `IllegalStateException` caught for control flow, no branch on message text, no broad catch, silent default, or swallowed cancellation, and every fallback emits a record.
- [ ] A8: no direct time, environment, property, or process read.
- [ ] A9 and A10: no new cycle, stutter, or fragment package, and tests sit beside their code.
- [ ] A11 and A12: new declarations are as narrow as their consumers allow, and inner layers expose no raw map or `Any`.
- [ ] G1 to G7: no guard, fixture, baseline, exemption, or allow-list was weakened.

## 6. Enforcement gaps to close

These rules are review-only today, or covered for only some modules. Closing them moves
the largest recurring classes from section 1 to mechanical checks.

| Rule | Gap | Planned or proposed check |
| --- | --- | --- |
| A3 | The inject-property rule covers application and cli only | SKILL-390 adds engine; extend it through `moduleArchitectureScanCases` to every module |
| A3 | The data-class collaborator rule covers cli only | SKILL-392 adds it for cli; generalize it the same way |
| A9 | Exact-package SCC is on for domain and contracts only | SKILL-392 adds cli; make it the default for every module |
| A10 | Test-package mirroring is guarded for runtime-core only | SKILL-389 adds runtime-core; the 2026-09-25 decision rejected a general guard and needs revisiting with this evidence |
| A12 | `Any` erasure evades the raw-map scan | SKILL-397 |
| A4 | The ports declaration guard misses repository-driving functions | SKILL-393 |
| A7 | Nothing stops a new custom throwable, and control-flow catches of `IllegalArgumentException` or `IllegalStateException` are found only by review | SKILL-398 adds the two-sided custom-throwable baseline; the catch rule stays review-only |
| G2 | Not every filtered scan asserts a non-empty file set | One shared helper in `ArchitectureScanSupport` that each scan calls |
| A2 | Ownership has no check | A consumer-count report on `runtime-contracts` and `runtime-ports` additions, run as part of review |
| A5 | Forwarders and unearned interfaces have no check | Review-only; P3 and P4 carry the weight |
