---
name: review-skill-structure-standard
description: Testable authored-source requirements for platform-pack review skills.
---

# Review Skill Structure Standard

This standard governs authored `content.md` sources for platform-pack review
skills. Generated wrappers and pointers remain outside this source contract.

## Specialist Skeleton

Every specialist uses these H2 sections in order: `Focus`, `Ignore`,
`Applicability`, and `Project-Specific Rules`. `Repo-Local Knowledge` may be
the only trailing optional H2. Rules are grouped by H3. Each specialist states
concrete API-boundary or failure-mode checks appropriate to its area; `ui` and
`ux-accessibility` explicitly defer concerns owned by the other lane and the
`security` lane instead of duplicating them. Specialists do not invoke sibling
specialists. The final specialist rule uses
`For Blocker or Major findings, describe the concrete <consequence> scenario.`
with this canonical area consequence:

| Area | Consequence |
|---|---|
| `architecture` | `dependency-cycle or ownership-boundary failure` |
| `performance` | `latency, memory-pressure, or throughput failure` |
| `platform-correctness` | `invalid-state or ordering failure` |
| `security` | `authorization-bypass or data-exposure` |
| `testing` | `undetected-regression or false-positive test` |
| `api-contracts` | `compatibility or validation failure` |
| `persistence` | `data-loss, consistency, or durability failure` |
| `reliability` | `availability, duplication, or cleanup failure` |
| `ui` | `user-visible interaction or rendering failure` |
| `ux-accessibility` | `accessibility or task-completion failure` |

Specialist content inherits the calibrated severity definition from the shared
contract and must not define, restate, or extend the severity vocabulary. The
canonical per-area consequence closer above is the required consequence
expression; every specialist uses it verbatim without introducing local
severity definitions, legends, or tables.

Specialist content describes the work a lane does over the bundle it is handed,
never how that bundle was discovered. The parent owns discovery, relevance, and
commit-to-lane routing; a specialist receives one assembled bundle of its
assigned hunks with commit identity as readable metadata and reviews it in a
single pass. Specialist content therefore must not instruct a worker to run
broad diff discovery, re-decide which commits or files are relevant, step
through commits as separate review steps, or restart from a whole-PR or
aggregate diff. Cross-commit behavior belongs to the single integration pass
described in `specialist-contract.md`, not to any specialist skeleton.

### Code-Quality Lane

The code-quality lane reports every finding as `Minor`, with no `Blocker` or `Major` ratings, and reports at most five findings per review. It is exempt from the reachable-failure rule that applies to other lanes. Its final rule is exactly `- Report every code-quality finding as Minor; never Blocker or Major.`

### Lane-Specific Consequence Examples

The lanes with the widest observed Major-to-Blocker spread benefit from
explicit examples distinguishing a material defect from an observation:

- **ux-accessibility** (observed 38:1 Major-to-Blocker spread):
  - Material defect (Major): A change that removes semantic markup, breaks
    keyboard navigation flow, or drops an ARIA relationship such that a
    demonstrated assistive-technology user cannot complete the task.
  - Observation (Minor/Nit or admission-gate suppressed): Missing or
    suboptimal ARIA labels where the control remains operable, or color-contrast
    findings below the AAA threshold without a demonstrated user failure.

- **data_persistence** (observed 20:1 Major-to-Blocker spread):
  - Material defect (Major): A change that introduces lost-update windows,
    violates isolation guarantees under a demonstrated concurrent scenario, or
    drops durability constraints such that committed data may be lost.
  - Observation (Minor/Nit or admission-gate suppressed): Cosmetic query-plan
    concerns where correctness and durability guarantees hold, or logging that
    mentions persistence without a concrete failure mode.

Severity-anchored ratings use the closed enum `Blocker`, `Major`, and `Minor`;
incidental prose that is not assigning a rating is outside that rule.

## Baseline Skeleton

Every baseline uses these H2 sections in order: `Classification Rules`,
`Diff-Signal Routing Table`, `Mixed Diffs`, and `Finding Discipline`.
Classification names explicit `if` decisions and an `otherwise` outcome. The
routing table maps concrete file-level diff signals to every declared
specialist. Vague instructions to route to a relevant specialist do not
conform. Mixed diffs keep the
baseline specialists for the whole review while using a lightweight file-level
classification pass for specialist selection. Per-specialist scope excludes
generated, vendored, and non-stack-owned files. Finding discipline calibrates
severity, verifies each finding's preconditions, preserves lane attribution,
and only then deduplicates overlapping findings.

## Manifest Conventions

`platform.yaml` declares every baseline and specialist `content.md`, the
approved review areas, routing signals, and generated pointers. It does not
declare generated source files or use pack prose to override routing contracts.
Every file-extension routing signal appears in both bare (`.kt`) and glob
(`*.kt`) forms. When routing signals overlap, tie-breakers state a positive
dominance rule, a negative disambiguation rule against adjacent packs, and an
exclusion of generated and vendored files from dominance scoring.
`area_metadata.focus` is bespoke to the stack and area: it names concrete
routing-signal context and is not merely the generic area focus with or without
the display label. Concrete bespoke metadata need not repeat the display label.

## Native-Agent Description Pattern

Each provider-neutral specialist description is derived from `platform.yaml`:
`{display_name-or-platform} {declared area} specialist — {area_metadata.focus}.`
The separator is an em dash surrounded by single spaces, and the description ends
with a period. Every declared specialist has exactly one matching entry. Generated
provider outputs are never authored or committed. The baseline owns a required
`native-agents/agents.yaml` source bundle; omission is a conformance failure.

## Quality-Check Skeleton

Every declared quality-check source has `Purpose`, `Execution Steps`, and
`Fix Strategy` H2 sections. It discovers commands from repository build files,
wrappers, and CI configuration before falling back to defaults; identifies the
scoped files; runs the pack entrypoint; uses a priority-ordered fix ladder;
never suppresses failures; reruns targeted checks; and escalates to the full
suite when the targeted result cannot establish safety.

## Authored-Sidecar Contract

A specialist may own one co-located authored Markdown rubric sidecar only when
its `content.md` explicitly names the sidecar and why a normal H2 section is
insufficient. The sidecar contains specialist rubric content only. It cannot
replace `content.md`, masquerade as a generated pointer, contain wrapper or
provider output, or become an arbitrary organization file.
