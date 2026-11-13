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

## [2026-11-13] Require ownership evidence before replacing or retiring skill links
Context: A user's existing sona target can block installation while an owned skill-bill link still supplies a usable dispatcher.
Decision: Preserve unowned targets and return typed install issues through every affected caller. Retire owned obsolete dispatcher links only after public linking succeeds, and retain previous link targets for scaffold rollback.
Reason: A reserved public name does not establish filesystem ownership. Preserving the old payload keeps a refused upgrade usable; reporting failure prevents false success and lets the operator resolve the conflict before retrying. Scaffold must restore pre-existing links when its transaction fails.

## [2026-11-13] Remove MCP configuration by product server ownership
Context: The namespace migration must replace old and new product registrations without losing unrelated profile settings. Product TOML configuration can include nested tables beside other servers.
Decision: Registration and removal recognize both product names. TOML removal matches parsed table paths rooted at either product server and tracks multiline values before treating a line as a table header.
Reason: Exact root-header removal leaves legacy environment or tool tables behind. Removing text through an imprecise boundary can delete neighboring configuration or mistake header-like string content for a section.

## [2026-11-13] Reconciliation hashes and staging reads stay uncommented
Context: Validation flagged KDoc on ReconcileSkillEntry, baselineSourceHash, installedStagingSourceReader, and computeReconciliationSourceHash, plus line comments on latestPublishedStaging.
Decision: Keep the names as the documentation. The composite hash covers authored files, pointer targets, internal children and addons. sourceHash covers only authored files. Missing or ambiguous published staging reads as edited. latestPublishedStaging uses the newest staging for that source dir rather than walking agent links.
Reason: Comment policy allows KDoc only on interfaces and their members. Line comments are forbidden in scoped Kotlin.

## [2026-11-12] Refuse to guess the Devin root on Windows
Context: Devin keeps skills, agents and MCP config under one root, `%APPDATA%\devin` on Windows. A missing or relative APPDATA would otherwise resolve under the home directory or the working directory.
Decision: devinConfigRoot needs a nonblank absolute APPDATA on Windows, or it throws AGENT_DISCOVERY_FAILED with recovery guidance before any write. An explicit environment map that lacks APPDATA fails instead of reading the ambient environment. Only an empty map falls back to the host environment.
Reason: Splitting skills, agents and MCP across a guessed root leaves an installation the provider cannot load and cannot cleanly remove. Real Windows hosts set APPDATA, so the refusal shows up only for broken environments and test stubs.
Alternatives considered: Falling back to home/AppData/Roaming guesses a layout. Skipping Devin silently hides a requested target and makes all-provider removal incomplete.

## [2026-11-12] Fail typed on malformed MCP JSON for every JSON provider
Context: McpJsonConfig read configs through readJsonObject, which threw IllegalArgumentException for malformed documents and replaced an `mcpServers` member that was not an object. Devin's dedicated config holds operator settings that must survive.
Decision: register and unregister parse through a private reader that throws MALFORMED_MCP_CONFIG before any write when the document is not a JSON object or a present `mcpServers` is not an object. The shared readJsonObject used by GovernedReviewMcpConfigWriter is unchanged.
Reason: One code path keeps Claude, Junie, Cursor and Devin consistent, and the original bytes stay put while the user gets a handled diagnostic.
Alternatives considered: A Devin-only reader would leave other providers silently replacing operator settings. Changing readJsonObject would widen scope to the launcher's review MCP writer.

## [2026-11-12] Pass host facts as function parameters, not on link requests
Context: Devin's native agents directory depends on the OS and APPDATA. NativeAgentLinkRequest and the inventory paths carry no host facts, and the architecture rules forbid ports inside DTOs.
Decision: NativeAgentProvider.homeAgentDirs, inventoryHomeAgentDirs, activeHomeAgentDirs and devinConfigRoot take a HostPlatformPort parameter defaulting to JdkHostPlatformPort, following resolveEnvironmentMap. Adapters that already hold an injected port pass it explicitly: planning facts, agent targets and paths, agentDirectory, and MCP registration.
Reason: Request DTOs stay free of ports, and tests can inject a Windows host where an adapter owns one, without changing any port signature.
Alternatives considered: Host fields on NativeAgentLinkRequest break the DTO rule. A required parameter at every call site changes unrelated callers that have no injected port.
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
