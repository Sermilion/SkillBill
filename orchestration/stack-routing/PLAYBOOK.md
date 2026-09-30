---
name: stack-routing
description: Single source of truth for shared stack detection. Discovery-driven — platforms are enumerated from platform-packs/ manifests, not hardcoded here. Installed skills link to this via generated support pointers.
---

# Shared Stack Routing Contract

This is the canonical stack-routing contract. Skills consume it through
generated sibling support pointers (e.g. `stack-routing.md` inside each staged
skill directory), so changes here propagate to every linked skill after
render/install refresh.

Do not reference this repo-relative path directly from installable skills —
use the generated sibling support pointer instead.

The stack taxonomy, strong signals, and tie-breakers are **not enumerated
inline**. They are discovered at runtime from the manifests shipped under
`platform-packs/<slug>/platform.yaml`. Adding, removing, or renaming a
platform happens entirely in those manifests; no edit to this file is
required unless the discovery algorithm itself changes.

## Discovery Algorithm

Every router, reviewer, and validator agrees on the following procedure:

1. Enumerate every immediate subdirectory of `platform-packs/`, resolved from
   the skill-adjacent `platform-packs` link installed next to this skill (or
   from the skill-bill install root `platform-packs` tree / the install-published review catalog).
   Do **not** treat the reviewed repository root's `platform-packs/` as the
   discovery root unless that repository is itself the Skill Bill source tree.
2. For each candidate slug, load
   `platform-packs/<slug>/platform.yaml` via the shell+content contract
   loader. Loud-fail rules apply — see
   [shell-content-contract.md](../shell-content-contract/PLAYBOOK.md). The
   loud-fail must name the slot key and the fully resolved absolute path.
3. Each loaded manifest contributes:
   - `platform` — the stack slug.
   - `routing_signals.strong` — list of strong detection signals (path
     markers, dependency coordinates, language-level markers).
   - `routing_signals.tie_breakers` — list of prose rules that disambiguate
     this pack against overlapping packs.
4. Collect signals from the review scope: changed files, repo markers, and
   dependency manifests — in that order.
5. Match literals at lexical or path boundaries and match manifest globs against
   normalized paths and basenames. Rank candidates by ownership evidence and
   signal specificity, not repeated occurrences or incidental manifest order.
6. Apply each candidate pack's declared tie-breaker rules in the order they
   appear in the manifest. When a pack declares a tie-breaker that subsumes
   another pack (for example, "prefer this pack when it layers the Kotlin
   baseline internally"), the subsuming pack wins.
7. If multiple packs have strong signals and no tie-breaker resolves the
   conflict, treat the scope as mixed and route to each matching pack via
   delegated execution.
8. If no concrete pack has positive path ownership, route to the unique pack
   whose manifest declares `fallback_capabilities: [code-review]`. The shipped
   `generic` pack is the default distribution choice, not a hard-coded slug.
   Documentation-only and unsupported paths use this same fallback.
9. Content signals cannot create ownership. They only break ties between packs
   with equal positive path matches. Concrete and composed winners never have
   the fallback appended.
10. The routed skill name for the selected pack is
   `bill-<slug>-code-review`. This contract is preserved so existing
   user-facing commands keep working.

Quality-check routing selects exactly one dominant pack and then runs that pack's
manifest-declared `validation_gate` through `skill-bill phase validation`. Ordinary Kotlin/JVM
ownership selects Kotlin; multiplatform source sets, plugin coordinates, or
`expect`/`actual` ownership select KMP. When one scope contains both Kotlin/JVM
and KMP ownership, KMP wins and its gate supplies the commands. Other unresolved
mixed-stack ties fail explicitly instead of choosing by order. A winning pack
without `validation_gate` raises a typed missing-gate error; quality checks never
fall back to another pack or a sidecar.

## Signal Collection Order

When classifying stack or platform:

1. Inspect the changed files first.
2. Then inspect repo markers and dependency manifests.
3. Prefer strong platform markers (as declared in each manifest's
   `routing_signals.strong`) over generic language markers.
4. When signals are mixed, keep the routing explicit instead of collapsing
   different packs into one bucket.

## Post-Stack Add-Ons

- Resolve governed add-ons only after the dominant stack route is chosen.
- Add-ons never create new top-level stack labels, package names, or
  user-facing commands.
- Add-on detection must be owned by the routed platform pack and reported
  separately from stack classification.
- When no governed add-on applies, report `Selected add-ons: none`.
- When one or more governed add-ons apply, report them as
  `Selected add-ons: <slug[, slug]>`.

## Relationship To The Shell+Content Contract

This playbook describes **how** the router decides. The detailed schema that
every `platform.yaml` must satisfy is owned by
[shell-content-contract.md](../shell-content-contract/PLAYBOOK.md). Loud-fail
loading rules, contract versioning, and manifest schema live there. If a
manifest is invalid, the shell refuses to run and the router has nothing to
decide.
