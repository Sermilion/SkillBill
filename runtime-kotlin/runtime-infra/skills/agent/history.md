# Boundary history

## [2026-10-05] SKILL-401 subtask 6: infra-skills install and authoring
Areas: runtime-infra/skills install staging and native-agent inventory, native-agent composition and rendering, scaffold authoring and validation; runtime-infra/host JVM file primitives; runtime-domain agent add-on model
- Staging input rejection emits the existing InvalidInstallStagingError directly. Native-agent bundle, toolset and composition input rejection uses INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA; review validation handles only that code.
- Inventory path parsing catches InvalidPathException. Stream-backed cleanup handles UncheckedIOException; atomic-move restoration no longer catches IllegalStateException. Existing I/O logging and error accumulation remain.
- Authoring and scaffold rollback, native-agent staging cleanup and host atomic-write cleanup use Closeable.use to retain the initiating failure and suppress cleanup failures. Staging backup restoration also retains deletion failures as suppressed.
- Pattern followed: reject external input through owned coded failures or nullable lookup; leave runtime invariant defects to propagate.
- reusable: AgentAddonConsumer.fromIdOrNull and unknownIdMessage share consumer lookup and rejection text across scaffold validation, authoring rendering and the existing throwing fromId wrapper.
- Compatibility: unknown-consumer and composition reason text remain unchanged. No wire schema, persisted format or generated output format changes.
- Limits: config stores and validateReleaseRef remain outside this subtask. Cleanup now covers cancellation and other failures previously skipped.
Feature flag: N/A
Acceptance criteria: 3/3 implemented
