# SKILL-383 Subtask 1 - Re-parent sidecars and the quality-check route to skill-bill

Parent spec: [spec.md](spec.md)
Issue key: SKILL-383

## Scope

Pack specialists install today as sidecars inside the installed `bill-code-review`
directory, and install policy keys on that skill existing. Quality-check overrides
must be sidecars of `bill-code-check`. Subtask 2 deletes both parents, so every
sidecar and every rule that names them moves to `skill-bill` first, while the old
parents still exist.

**Frontmatter.**
- The 97 platform-pack `content.md` files that declare `internal-for: bill-code-review`
  declare `internal-for: skill-bill`.
- `skills/bill-code-review-inline/content.md` declares `internal-for: skill-bill`,
  unless SKILL-380 subtask 8's census found no production caller; then subtask 2
  deletes it and this subtask leaves it alone.

**Install policy.** Every check that keys on the `bill-code-review` parent keys on
`skill-bill`:

- `InstallService` (`skills.any { it.name == "bill-code-review" }`)
- `InstallPlanBuilder` (same check)
- `InstallPlanPolicyResolution` in runtime-domain
- `InternalSidecarTarget` and the platform-pack install staging paths that resolve the
  parent directory

**Loaders and scaffold.**
- `ShellContentLoaderFields`: the quality-check family requires
  `internal-for: skill-bill`.
- `ReviewSkillStructureValidatorContent`: `hasInternalParent(file, "skill-bill")`.
- `ScaffoldServiceRollbackPlatformPack` and the scaffold renderers emit
  `internal-for: skill-bill` for new pack skills.
- `routeQualityCheck` / `QualityCheckRoute` has no production caller on 2026-09-25
  (only `QualityCheckRoutingTest` and `ExternalPlatformPackCatalogIntegrationTest`).
  Recheck; if still uncalled, delete it with its test cases, otherwise retarget its
  shell constant to `phase:validation` keeping pack slug and matched signals. The MCP
  `quality_check_*` `routed_skill` label stays `bill-code-check`.

**Readers of installed sidecars.** Census every runtime reader that locates rubrics,
specialist content, or native-agent sources under the installed parent directory
(review rubric planning, native-agent generation, install reconcile) and point it at
the `skill-bill` install directory.

**Tests.** Update the repo tests that pin the old parent:
`QualityCheckRoutingTest`, `ReviewSkillStructureConformanceTest`,
`NativeAgentCompositionValidatesExistingBundlesTest`, and any other the census finds.

Telemetry labels (`bill-code-review` in `McpAdapterContracts`, `bill-code-check` in
`LifecycleTelemetryPayloads`) stay (parent criterion 5).

## Acceptance Criteria

1. No file under `platform-packs/` or `skills/` declares `internal-for: bill-code-review` or `internal-for: bill-code-check`.
2. A test installs into a temporary `HOME` and asserts every pack specialist (and `bill-code-review-inline`, if it is kept) lands under the installed `skill-bill` directory, and none under `bill-code-review`.
3. A loader test asserts a pack quality-check override declaring `internal-for: skill-bill` loads, and one declaring `internal-for: bill-code-check` is rejected with a message naming `skill-bill`.
4. `routeQualityCheck` is deleted if it still has no production caller; otherwise a routing test asserts every maintained dominant stack routes to `phase:validation` with the pack slug unchanged.
5. The runtime readers that locate specialist rubrics and the inline worker resolve them from the installed `skill-bill` directory, and a test asserts `skill-bill code-review` and `skill-bill phase review` find them there. The SKILL-380 subtask 8 review fixture baseline is not changed in this subtask.
6. The scaffold renderers emit `internal-for: skill-bill` for a new pack skill, and a scaffold test asserts it.

## Non-goals

- Deleting `skills/bill-code-review`, `skills/bill-code-check`, or any other tree (subtask 2).
- Renaming specialist skills, the inline worker, or telemetry labels.
- Changing rubric content.

## Dependency notes

- First subtask. `skill-bill` is already an installable listed parent (SKILL-380
  subtask 12). Local clone for Spotless.

## Validation strategy

Catch: sidecars installing nowhere; review failing to find a rubric; a quality-check
override silently ignored; routing naming the deleted shell. Cover with the
temporary-`HOME` install test, the loader rejection test, the routing test, and a
standalone review run over a fixture pack. Run `cd runtime-kotlin && ./gradlew check`
plus infra-skills, CLI, application. `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_2_retire-listed-skills.md`.

## Spec Path

.feature-specs/SKILL-383-single-skill-catalog/spec_subtask_1_sidecar-reparenting.md
