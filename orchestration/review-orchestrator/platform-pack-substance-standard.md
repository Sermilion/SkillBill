# Governed Platform-Pack Substance Standard

Audit contract version: `0.1`

This standard is the maintained-repository substance gate for platform packs. It
does not change the structural contract accepted by the platform-pack loader.
Every requirement below is independently measured by the canonical audit used by
tests and `skill-bill validate`.

## Corpus and effective coverage

The audit enumerates every non-hidden immediate directory under `platform-packs/`;
a present directory without `platform.yaml` is an audit error. It reads only
manifest-declared authored `content.md` files plus a specialist-owned Markdown
sidecar explicitly linked by that content. Generated `SKILL.md` wrappers, generated
pointer files, resolved shared targets, provider output, add-ons, and boundary
memory are excluded.

Physical areas are the manifest's local `declared_code_review_areas`. Inherited
areas are reachable through an acyclic chain of required baseline layers. A layer
contributes coverage only when its target pack exists, its declared baseline skill
matches the layer skill, and that target's effective graph supplies the area.
Optional, missing, mismatched, and cyclic layers supply no required coverage. A
maintained pack must effectively cover every approved failure area applicable to
its declared composition. Maintained repository packs also own a local
`code-quality` declaration and source; inherited quality does not satisfy that
ownership rule. The structural loader still accepts legacy custom packs without a
local owner so they can use the installed generic fallback.

## Specialist depth

Each physical failure specialist must contain at least ten substantive
enforceable rules and represent all three failure-mode clusters:

1. state, lifecycle, concurrency, or ordering failures;
2. contract, data, validation, authorization, or security failures;
3. resource, performance, toolchain, build, or operational failures.

A substantive rule is one Markdown bullet under a governed rule H3. In the same
bullet it must contain an obligation or prohibition, concrete platform evidence,
and an explicit failure or observable consequence. Concrete evidence is a
backticked or command-form named API, language construct, framework, build tool,
configuration surface, or toolchain command. The pack name alone is not evidence.
Each represented cluster must contain concrete platform evidence.

The `code-quality` specialist also needs at least ten substantive rules, but its
rules name a concrete maintenance cost and a behavior-preserving rewrite instead
of requiring a reachable failure. Its three concern clusters cover state and
lifecycle cohesion, contracts and value handling, and resources and responsibility
size. Every rule names a language construct, framework, or repository convention.
Renaming generic prose does not count as platform guidance.

The tokens `TODO`, `FIXME`, `TBD`, and `XXX` are forbidden, as are generic
`mechanism`, `API`, `example`, or `command` placeholders and prose that asks an
author to fill or replace content. A placeholder-bearing file fails regardless of
its other metrics.

Quality-check commands belong to each pack's optional `validation_gate`; they are
not review specialists and do not require authored quality-check skill files.

## Deterministic normalization and duplication

Normalization removes YAML frontmatter, applies Unicode NFKC, lowercases with
`Locale.ROOT`, removes Markdown link destinations while retaining link labels,
normalizes Markdown syntax, converts punctuation to spaces, and collapses
whitespace into stable tokens. The current slug, display name, and governed skill
names become role placeholders, so names alone neither create nor avoid
similarity. Code and command tokens remain lexical content because they are
substantive evidence.

The audit builds a set of unique contiguous five-token shingles. Fewer than five
tokens yields an empty set and zero similarity. A pack's shared-shingle percentage
is the number of its unique shingles present in any other maintained pack divided
by its unique shingle count. Corresponding authored rubrics match by manifest role:
baseline, quality-check, same-area specialist, or explicitly referenced
specialist-owned sidecar. Pair similarity is symmetric Jaccard intersection over
union.

Exact rational values are compared before formatting. Reports round half-up to two
decimal places. Exactly 35% pack-shared shingles and exactly 65% corresponding-pair
similarity pass; values strictly above those thresholds fail. Pair violations name
both authored files.

## Enforcement

Every audit violation blocks maintained validation. The normal substance gate has
no acknowledgement, baseline, exemption, wildcard, or permanent suppression path.
