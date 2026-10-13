# SKILL-421 Code-quality lane in every platform pack

## Outcome

Every shipped platform pack owns and declares a code-quality specialist with guidance for its language and frameworks. A routed review uses that specialist rather than relying on the generic fallback to supply code-quality coverage. Composition selects exactly one code-quality specialist, including for KMP's Kotlin baseline composition.

The lane keeps SKILL-413's finding discipline, report section, and telemetry category. Its findings remain non-blocking, Minor severity, and capped at five per review. Existing failure lanes retain their behavior.

## Problem

SKILL-413 landed a generic code-quality lane that composition appends to routed reviews. Only `../../../platform-packs/generic/platform.yaml` declares it. Other packs have no owned specialist, and Kotlin's optional idioms sidecar is not automatically included in every worker launch. Pack authors and operators cannot inspect a platform-specific code-quality lane alongside the pack's other specialists.

The current composition already supports a graph-owned universal area taking precedence over the fallback. This change uses that ownership model and closes authoring, registration, delivery, and validation gaps without building a second review composition mechanism.

## Scope

- Pack-owned code-quality specialists and manifest registrations in every shipped pack.
- Provider-neutral native-agent declarations, launch-provided rubrics, installed sidecars, and provider-native generation for those specialists.
- Composition and cross-root reconciliation that select one code-quality lane with the correct owner.
- Scaffolding and maintained-pack coverage checks that prevent newly authored packs from omitting the lane.
- Documentation, routing fixtures, rendered snapshots, installation, and focused regression coverage.

The shipped packs at specification time are `generic`, `go`, `ios`, `kmp`, `kotlin`, `php`, `python`, `rust`, and `typescript`. This inventory describes current scope; discovery and validation remain manifest-driven rather than embedding this list in runtime routing.

## Pack content

Each pack owns `platform-packs/<slug>/code-review/bill-<slug>-code-review-code-quality/content.md`, declared with `internal-for: skill-bill`. Register `code-quality` in the pack's declared areas, area file mapping, metadata, required lane condition, and any other existing manifest surfaces needed for rubric delivery and native-agent generation. Generic keeps its existing specialist and fallback role.

Every specialist covers idiomatic constructs, helper clarity, cohesive state, duplication, redundant branches, dead code, responsibility size, and concrete naming problems. It follows project conventions over generic preference and ignores matters already enforced by configured linters or formatters. Each finding names a concrete maintainability cost, a location, and a behavior-preserving rewrite. A reachable behavioral failure is not required.

Platform guidance must be substantive. Changing the generic rubric's name is insufficient. Examples should use the pack's language and relevant framework idioms, while avoiding duplication of behavioral findings owned by other specialists:

| Pack | Guidance emphasis |
|---|---|
| Generic | Language-derived idioms and project conventions when no concrete platform owns the lane. |
| Go | Clear error handling, small interfaces, value and pointer choices, explicit ownership, and standard-library operations. |
| iOS | Swift value modelling, optional handling, enums, focused extensions, SwiftUI state clarity, and readable task ownership. |
| KMP | Kotlin idioms in Android and multiplatform code, Compose state cohesion, clear Flow usage, and expect/actual organization. |
| Kotlin | Kotlin/JVM idioms, collection and null handling, sealed lifecycle state, coroutine structure, and backend framework conventions where detected. |
| PHP | PHP type and value handling, focused classes, generators, and detected Symfony or Laravel conventions. |
| Python | Python data modelling, context managers, comprehensions where readable, focused functions, and detected framework conventions. |
| Rust | Ownership clarity, enums, iterators, pattern matching, error composition, and avoiding unnecessary clones or indirection. |
| TypeScript | Discriminated unions, narrowing, async control flow, module structure, and detected UI or server framework conventions. |

KMP owns an authored `bill-kmp-code-review-code-quality` specialist even though it composes Kotlin for other review areas. Its local specialist wins over Kotlin's inherited code-quality lane. Language and framework guidance reaches the actual reviewer through the authorized launch packet; workers do not need unrestricted filesystem reads to discover idiom files. Incorporate relevant Kotlin idioms into the owned rubric or an existing contract-authorized required companion. Do not depend solely on the optional sidecar or introduce an unauthorized sibling guidance file.

## Composition and compatibility

Resolve code-quality ownership through existing manifest composition. The nearest declared owner wins, preserving current ambiguity and missing-layer rules. Append generic code-quality only when the routed composition has no owner and the installed fallback declares it. Never append generic alongside a selected platform-owned code-quality lane.

The lane remains required and additive in every shipped pack. Selection heuristics cannot drop it solely because the diff lacks a particular framework token. A review with no applicable code can report no code-quality findings without inventing work.

Non-code-quality lane identity, ownership, order, requiredness, routing signals, add-on selection, and failure findings remain unchanged. Cross-root reconciliation must preserve one selected code-quality lane without allowing duplicate findings from an inherited or fallback owner. Inline review receives the selected platform guidance; delegated review launches the matching specialist. Standalone review, workflow review, and verify's existing code-review evaluator use the same composition behavior.

Older or external custom packs without a local code-quality specialist continue using an installed manifest-declared fallback when available. Absence of both a local owner and a fallback retains the existing documented behavior rather than causing an undocumented runtime refusal. Shipped-pack coverage and new-pack scaffolding enforce the new authored default without hard-coding runtime slugs or silently rewriting user-owned packs.

Do not extend `APPROVED_CODE_REVIEW_AREAS` merely to force a substance-audit count. Code-quality is already represented by `UNIVERSAL_CODE_REVIEW_AREAS` and accepted by the platform manifest schema. Keep one owner for that vocabulary and enforce local ownership through the appropriate maintained-pack checks. Change canonical schemas and versions only if implementation proves a wire-shape change is required.

## Findings and reporting

Keep the existing separate code-quality risk-register section after failure findings, sequential F-ids, specialist attribution, and `code_quality` telemetry category. Deterministic merging normalizes all platform code-quality findings to Minor and enforces the five-finding cap across the whole review, not five per provider or composed layer.

Failure findings and code-quality findings remain separate during deduplication. Code-quality cannot suppress a behavioral finding or change the review verdict. Report-only standalone review and verify do not auto-fix code-quality findings. Existing workflow repair rules are not broadened by this change.

## Acceptance criteria

1. Every currently shipped platform pack declares and owns a structure-conformant `bill-<slug>-code-review-code-quality/content.md` specialist. Generic remains the fallback owner. KMP has its own authored specialist rather than satisfying coverage only by inheriting Kotlin's.
2. Each non-generic rubric contains substantive language and framework guidance appropriate to its pack, covers the common maintainability concerns, follows repository conventions, and proposes behavior-preserving rewrites without requiring a reachable failure. Maintained-pack substance validation rejects a missing or generic-only renamed rubric.
3. Each pack registers the specialist through the existing manifest-owned declared area, file, metadata, requiredness, and native-agent surfaces. Invalid declarations, missing content, or missing required sections fail through existing typed validation boundaries.
4. Review composition for each shipped pack selects exactly one code-quality lane owned by that pack. KMP selects its own lane over Kotlin's baseline lane; Kotlin selects its own rather than generic. Existing graph ambiguity rules remain enforced.
5. A legacy custom pack with no code-quality owner gets the installed generic fallback when available. A pack with its own owner works without generic installed. The absence of both retains documented compatibility behavior and emits required degradation records.
6. Inline and delegated reviews receive the selected owner's full authorized rubric, including required language guidance. No selected lane relies on an optional file that its launched worker cannot access.
7. Generated provider-native agents and installed internal sidecars include the new specialist for every selected pack. Native-agent preflight verifies the selected owner and does not silently launch the generic agent instead. Removed packs leave no stale managed links.
8. Standalone review, workflow review, and verify's code-review evaluator use the same selected code-quality owner. The dispatcher still exposes only `/skill-bill`; new specialists remain unlisted sidecars and native-agent inputs.
9. Merging platform-owned findings preserves Minor severity, an aggregate cap of five, a separate code-quality report section, `specialist=` attribution, and `code_quality` telemetry. Existing failure findings, verdicts, and the `quality` telemetry alias retain their meanings.
10. Non-code-quality lane sets and ownership are identical before and after this change for every shipped composition. Adding local code-quality ownership does not union generic's other lanes into platform reviews or change existing routing signals.
11. New full-pack scaffolding includes an owned, required code-quality specialist using the existing dynamic authoring model. Authored stubs still require meaningful filling and validation before shipping. Existing custom packs are not rewritten or made invalid solely for lacking the new local lane.
12. Maintained-pack coverage checks discover packs through manifests and reject removal of a shipped pack's local code-quality declaration or source. Tests prove rejection through the actual validator, not a copy of its algorithm. Source generation retains `content.md` authority and commits no generated pointers or provider-specific outputs.
13. Installation, render snapshots, catalogs, and docs describe the new local ownership accurately. Runtime resume retains accepted assignments or uses an explicitly checked compatibility mapping; adding a local lane cannot silently substitute an owner midway through an accepted review.

## Non-goals

- Adding or changing behavioral failure lanes, their Ignore lists, or their severity rules.
- Requiring a code-quality finding on every review or repeating formatter and linter findings.
- Making code-quality findings blocking, raising their cap, or changing review repair policy.
- Making generic a baseline layer of every pack.
- Automatically editing external user-owned packs.
- Extending the horizontal review path when no platform pack is installed.
- A new review orchestrator, transport framework, telemetry event, or database schema without a demonstrated requirement.

## Constraints and settled decisions

This spec supersedes SKILL-413's generic-only lane ownership and its non-goal of adding specialists to non-generic packs. It preserves SKILL-413's additive behavior and finding discipline. Implementation records that supersession in the owning `../../../agent/decisions.md` and verifies retained SKILL-413 criteria after the change.

Use existing nearest-owner composition and universal fallback handling. Delete obsolete generic-only assumptions in tests or docs instead of keeping contradictory assertions. Do not widen architecture exceptions, substance-audit exemptions, or source-generation permissions to ship the new rubrics.

Apply `../../../docs/runtime-command-guidance.md`, `docs/skill-source-generation.md`, `docs/architecture-guidelines.md` A1 through A12 and G1 through G7, `runtime-kotlin/ARCHITECTURE.md` Design Principles, `docs/code-principles.md`, and `docs/observability-policy.md`. Pack behavior stays manifest-owned. Runtime declarations and wire vocabulary keep one owner. Kotlin comments follow repository restrictions. Required schema changes land in canonical contracts first and fail loudly at parse seams.

Base branch is `main`, which contains the SKILL-380 and SKILL-413 merges. This request creates requirements only and does not start implementation or create a decomposition manifest. Planning preserves current SKILL-418 work and the SKILL-419/SKILL-420 QA specs.

## Validation strategy

Name the realistic regression before each new test. High-value checks cover local-owner precedence over generic, KMP precedence over Kotlin, exactly-one-lane reconciliation, pack-owned operation without generic, legacy custom-pack fallback, full rubric delivery to inline and delegated reviewers, required native-agent integrity, severity and aggregate cap, unchanged failure findings, and rejection of a shipped pack missing its specialist.

Reuse current composition, structure-conformance, substance-audit, merger, scaffold, native-agent, and installation tests where they already own these boundaries. Update snapshots only for intended code-quality additions. Use a manifest-discovered pack matrix rather than a runtime slug list. Run the repository's required maintainer checks and `./install.sh` for authored skill and generated-install changes. Verify installed specialist identity and content, rather than treating source presence as proof of launch availability.
