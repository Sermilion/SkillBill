## [2026-10-09] Keep code-quality findings Minor and capped
Context: Existing specialist rules require reachable failures and exclude style concerns, leaving maintainability without a review lane.
Decision: Give only the code-quality lane an exception to the meaningful-issue, style-nit and Minor-tie rules. Require a concrete smell, location and rewrite, Minor-only severity, at most five findings and no auto-fixing.
Reason: The settled spec requires quality advice to stay below behavioral defects and limits review noise. Project guidance takes precedence, and findings must not duplicate configured formatter or linter rules.
