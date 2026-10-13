## [2026-10-13] Give shipped packs local code-quality ownership
Context: SKILL-413 supplied quality only through generic, and optional Kotlin idioms did not reach every reviewer launch.
Decision: SKILL-421 supersedes SKILL-413's generic-only ownership and its non-goal of adding non-generic specialists. Every shipped pack owns required quality; existing nearest-owner composition selects KMP over Kotlin and uses generic only when no routed owner exists.
Reason: Pack authors and operators need inspectable language and framework guidance beside the other specialists. The existing graph already resolves universal-area ownership, so another composition mechanism or generic baseline would add no required behavior.
Alternatives considered: Depending on optional idiom sidecars cannot guarantee reviewer guidance. Generic-only ownership cannot supply each pack's authored language and framework rubric.

## [2026-10-13] Enforce local quality in maintained-pack auditing
Context: Shipped packs and new full-pack scaffolds must own quality, while older external packs may lack that declaration.
Decision: Enforce local quality through manifest-discovered maintained-pack auditing and full-pack scaffolding. Keep the structural loader compatible with custom packs; inherited quality cannot satisfy maintained local ownership.
Reason: The new authored default must not silently rewrite or invalidate user-owned packs. Existing fallback and recorded omission preserve compatibility without hard-coded runtime slugs, a schema bump or an expanded approved failure-area vocabulary.
Alternatives considered: A general loader requirement would reject legacy custom packs solely for lacking local quality. Counting inherited quality would allow KMP's local specialist to disappear.

## [2026-10-13] Deliver selected rubrics in review launches
Context: Inline, delegated, standalone and verify reviews must receive the selected owner's full language guidance through their authorized evidence boundaries.
Decision: Use the existing review preparation, rubric resolver and native-agent composition to supply full owned content and selected governed add-ons. Embed the relevant Kotlin idioms in Kotlin and KMP rubrics.
Reason: A selected specialist's name alone does not deliver its guidance. Workers must not need unrestricted checkout reads, and inline execution must not invoke the delegated runner. Shared preparation keeps owner selection consistent across entrypoints.
Alternatives considered: An optional sidecar does not guarantee delivery; an additional companion mechanism or review orchestrator duplicates existing responsibilities.

## [2026-10-09] Keep code-quality findings Minor and capped
Context: Existing specialist rules require reachable failures and exclude style concerns, leaving maintainability without a review lane.
Decision: Give only the code-quality lane an exception to the meaningful-issue, style-nit and Minor-tie rules. Require a concrete smell, location and rewrite, Minor-only severity, at most five findings and no auto-fixing.
Reason: The settled spec requires quality advice to stay below behavioral defects and limits review noise. Project guidance takes precedence, and findings must not duplicate configured formatter or linter rules.
