## Review mode argument

`delegated` and `inline` are two review depths, not two ways to execute the same
review. Report the requested mode and the resolved depth in the normal review
metadata.

`inline` is the default depth. `delegated` is the experimental full-depth tier and
runs only on an explicit `mode:delegated` from this skill. Goal and feature-task
runs never select it. Neither an omitted argument nor `mode:auto` ever reaches it.
Choose it when a change genuinely warrants per-area depth, not by default.

`delegated` always runs the normal routed delegated path
including specialist selection. Inability to launch a required native worker
blocks loudly; it never degrades to inline.

`inline` is the single-prompt light tier: one review session, no per-area
specialist workers, no nested baseline orchestrator, under a bounded budget at
reduced depth. The worker traverses the delta exactly once against one combined
checklist, holding all areas in mind simultaneously — it must never re-walk the
same delta once per area. Never present it as equivalent to a delegated result.

`auto` resolves to `inline` everywhere: a subtask's first review pass, a standalone
review with no pass number, and every follow-up or remediation pass. Preserve and
report the applicable named auto rule for telemetry. `auto` never reaches the
experimental delegated tier — only an explicit `mode:delegated` on this skill does.

Depth is the only thing the light tier lowers. The severity vocabulary, evidence
and observable-consequence requirements, F-XXX register guidance, and telemetry
are inherited unchanged and are never restated per tier. Register shape is
best-effort guidance. The phase result is the agent output string; the runtime
governs launch, evidence, and persistence rather than policing the format.

With `context:feature-remediation`, the pass is bounded to the supplied
remediation delta — all findings addressed in that round unioned with the
pre-fix-to-post-fix diff — rather than the full base-to-current delta, and
verification is its primary output. For every Blocker the prior pass emitted,
state `resolved`, `unresolved`, or `superseded` under the durable
`blocker_dispositions` key, and cite the specific changed lines that settle it.
A disposition without that evidence is not admissible.
