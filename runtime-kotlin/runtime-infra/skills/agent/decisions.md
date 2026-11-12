## [2026-11-12] Preserve OpenCode configuration and managed ownership
Context: OpenCode stores MCP settings in JSON/JSONC, and native-agent paths can contain user replacements.
Decision: Edit and validate only the managed subtree, recheck before atomic publication, and require recorded identity and target ownership for removal. Journal native-agent link mutations and preserve unrelated providers.
Reason: Generic reserialization loses comments and unrelated settings. A name or cache path alone does not prove ownership, and independent filesystem publications are not one transaction.

## [2026-11-12] Map neutral capabilities to OpenCode permissions
Context: OpenCode specialists need primary mode and provider-specific permission keys, while neutral sources declare capabilities.
Decision: Map declared capabilities to exact permissions after default denial, reject unmapped capabilities, and omit model and variant directives. Sources without tool declarations retain host defaults.
Reason: Exact keys preserve declared authority without granting every MCP tool. An invented model would override execution selection, and rendered permissions alone do not prove review isolation.

## [2026-11-12] Keep the additive OpenCode inventory extension at version 0.2
Context: Native-agent inventory adds an OpenCode provider without new required fields or changed record meaning.
Decision: Extend the provider enum in schema 0.2 and retain existing migration steps.
Reason: Existing populated 0.2 records satisfy the extended contract, so a version bump or rewrite would add no required transformation.

# Boundary decisions

## [2026-10-09] Stage optional code-quality idioms only from review baselines
Context: The Kotlin idioms sidecar must live beside its code-review baseline, whose content stays unchanged, while authored companions normally require an explicit content link.
Decision: Exempt only code-quality-idioms.md in a code-review baseline directory from the content-link requirement. Preserve path, symlink, count, reserved-name and collision checks for staging.
Reason: This resolves the required source location without relaxing companion rules generally, changing baseline content or adding a manifest key. Staging does not grant worker access; the lane uses supplied or authorized guidance and otherwise derives language idioms itself.

## [2026-10-05] Keep initiating failures primary during cleanup
Context: Authoring rollback and native-agent staging cleanup selected exception types, while bare finally cleanup could replace the failure that triggered it.
Decision: Establish cleanup ownership with Closeable.use immediately after acquisition. Authoring and scaffold rollback use a success flag; temporary staging and atomic-write files always receive cleanup.
Reason: Every failure exit, including cancellation and interruption, must run owned cleanup. use retains the initiating failure and attaches cleanup failures as suppressed.
Alternatives considered: A bare finally does not preserve primary failure ordering; separate IAE/ISE catches miss other failure exits.

## [2026-10-05] Reuse owned input failure codes without catching defects
Context: Staging preparation and native-agent composition converted IllegalArgumentException into handled input rejection, which also intercepted invariant defects.
Decision: Emit existing staging failures and INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA directly for rejected input. Review validation catches only the composition schema code and rethrows unrelated coded failures.
Reason: These boundaries already have a handled failure contract. Reusing it preserves edge classification and reason text without introducing throwables or broad defect recovery. Source-labelled composition framing applies once.

## [2026-10-05] Share nullable agent add-on consumer lookup
Context: Scaffold validation caught throwing consumer lookup; authoring pointer rendering used runCatching to interpret unknown consumers as absence.
Decision: Both callers branch on AgentAddonConsumer.fromIdOrNull. Keep fromId as the throwing wrapper, one unknownIdMessage source, the legacy decode alias and scaffold's explicit retired-consumer rejection.
Reason: Unknown consumers are expected lookup outcomes. Nullable lookup removes exception-based control flow while the shared message source preserves existing rejection text.

## [2026-10-05] Match cleanup recovery to filesystem failure sources
Context: Cleanup caught IllegalStateException even though Files.walk and Files.list can fail through UncheckedIOException; backup restoration performs an atomic move.
Decision: Handle UncheckedIOException at stream-backed deletion boundaries and retain existing IOException handling. Remove the defect catch from atomic-move restoration without adding a stream catch there.
Reason: Real I/O failures still need existing logs, accumulated errors and suppression. Recovering invariant defects as cleanup I/O hides bugs, and atomic moves do not justify stream-specific recovery.
