# SKILL-441 subtask 7: clickable spec bundle files in desktop goal activity and plan reports

## Scope

Render the runtime-supplied bundle file list in the desktop tray's goal activity entry and in
standalone plan run reports. Each file opens in the OS default application. The desktop uses the
absolute paths the runtime supplies and never joins a repository root with a relative path.

## Acceptance Criteria

1. Desktop navigation can open a file in the OS default application.
   `RepositoryNavigation` in desktop-tray core/ui `RepositoryNavigation.kt` gains `fun openFile(path: String)`, provided through `LocalRepositoryNavigation` like `openRepository` and `openDirectory`.
   The composeApp implementation in `NativeServicesImpl.kt` opens the file with `java.awt.Desktop.open` when the desktop and the `OPEN` action are supported, and otherwise runs `xdg-open` on Linux, `open` on macOS or `cmd /c start "" <path>` on Windows through the existing `runCommand`. A missing file or a failed launch is reported through the same failure path `navigate(failure)` and `openLocation` already use, and never crashes the tray.
   Rendering the links is criterion 3. Audit confirms by reading the interface and the implementation.
2. The desktop domain model carries the bundle files exactly as the runtime supplies them.
   desktop-tray core/domain `WorkModels.kt` adds `bundleFiles: List<String>` to `PlanRunReport` (near lines 453 to 458). core/data `RuntimeWorkMapper.kt` (`StandalonePlanReport.toDomain`, near lines 38 to 39, and the mapping near line 174) copies `bundleFiles`, `parentSpecPath` and each subtask `specPath` verbatim and does not resolve any of them against a repository root.
   A report from an older runtime maps with an empty `bundleFiles` list. Audit confirms by reading both files and finding no root-joining of plan report paths.
3. Goal activity and standalone plan reports list every bundle file as a clickable link.
   desktop-tray feature/detail `PlanReport.kt` (`PlanReportSection`) renders a "Spec bundle" list with one entry per `bundleFiles` path, labelled with the file name and showing the full path, and calls `LocalRepositoryNavigation.current.openFile(path)` on click. When `bundleFiles` is empty it falls back to the parent spec and subtask spec paths. Any path that is not absolute renders as plain selectable text without a click action.
   `DetailContent.kt` (the "Goal activity" and "Phase activity" panel, near lines 360 to 410) shows `PlanReportSection` on the goal activity entry once the runtime attaches a plan report after the plan phase, and keeps showing it for standalone plan executions in execution history.
   Audit confirms by reading both files.

## Criterion Sizing

1. About 50 production lines, 2 files, 2 areas (`desktop-tray/core/ui`, `desktop-tray/composeApp`). Fits.
2. About 30 production lines, 2 files, 2 areas (`desktop-tray/core/domain`, `desktop-tray/core/data`). Fits.
3. About 120 production lines, 2 files, 1 area (`desktop-tray/feature/detail`). Fits.

## Non-Goals

- Runtime production of the bundle file list (subtask 6).
- Editing files from the desktop.

## Dependency Notes

Depends on subtask 6 for `StandalonePlanReport.bundleFiles` and the goal plan-phase report attachment.

## Test Obligations

- `DetailPlanReportTest`: a report with absolute bundle files renders one clickable entry per file and clicking calls `openFile` with that exact path; a relative path renders without a click action. This catches the desktop joining or dropping paths.
- `WorkStackTest` fixtures that use `.feature-specs/...` relative paths are updated to absolute store paths where they assert links.

## Implementation Details

All paths are under `desktop-tray/`. Sites and line numbers come from the preplan digest. Implement
confirms them before editing. Run tasks in order: the domain field must exist before the UI reads it.

### Task 1. Domain model field (criterion 2)

- `core/domain/.../model/WorkModels.kt`, `PlanRunReport(featureName, parentSpecPath, subtasks)` near
  lines 453 to 458: add `val bundleFiles: List<String> = emptyList()`. The default keeps existing
  constructors and fixtures compiling and gives the "older runtime maps to empty" behavior a single
  home. `PlannedSubtask` is unchanged.
- Constraint: no path normalization, no `Path` type, no root parameter. The field holds the
  runtime strings as-is.

### Task 2. Verbatim mapping (criterion 2)

- `core/data/.../runtime/RuntimeWorkMapper.kt`, `StandalonePlanReport.toDomain()` near lines 38 to 39
  and the call site near line 174: pass `bundleFiles = bundleFiles`, `parentSpecPath = parentSpecPath`
  and each subtask `specPath = specPath` straight through.
- Remove any existing repo-root join on plan report paths if implement finds one (for example
  `root.resolve(parentSpecPath)` or string concatenation with a repository root). The digest does
  not show one at these lines. Implement confirms and records the outcome.
- Older runtimes: subtask 6 declares `StandalonePlanReport.bundleFiles` with an empty-list default
  and optional decode, so the mapper needs no null handling. Assumption for implement to confirm:
  the runtime-side field is non-null `List<String>` with default `emptyList()`. If subtask 6 made it
  nullable, map with `.orEmpty()`.

### Task 3. Open a file in the OS default application (criterion 1)

- `core/ui/.../RepositoryNavigation.kt`: add `fun openFile(path: String)` to the
  `RepositoryNavigation` interface next to `openRepository(root, branch)` and `openDirectory(root)`.
  Provide it through the existing `LocalRepositoryNavigation`. If the local has a no-op default
  instance, add a no-op `openFile` there too.
- `composeApp/.../infrastructure/NativeServicesImpl.kt`: implement `openFile(path)`.
  1. Build `File(path)`. If it does not exist, report a failure through the same `navigate(failure)`
     path `openLocation` uses and return.
  2. If `Desktop.isDesktopSupported()` and `Desktop.getDesktop().isSupported(Desktop.Action.OPEN)`,
     call `Desktop.getDesktop().open(file)`.
  3. Otherwise pick the command by OS name: `xdg-open <path>` on Linux, `open <path>` on macOS,
     `cmd /c start "" <path>` on Windows, and run it through the existing `runCommand`.
  4. Catch launch exceptions (`IOException`, `SecurityException`, `UnsupportedOperationException`,
     `IllegalArgumentException`) and a non-zero or failed `runCommand` result, and report them
     through the same failure path. Nothing propagates to the compose thread.
  - If `Desktop.open` blocks on the calling thread in the existing code style, follow whatever
    dispatching `openLocation` already does. Do not add a new threading scheme.
- Every other `RepositoryNavigation` implementation (test fakes, previews, any second production
  impl) gains `openFile`. Implement finds them by compile errors during its own edits. The build
  phase proves compilation.

### Task 4. Bundle file links in `PlanReportSection` (criterion 3)

- `feature/detail/.../PlanReport.kt`, `PlanReportSection(report)`:
  - Add a "Spec bundle" sub-list. Source list: `report.bundleFiles` when non-empty, otherwise
    `listOf(report.parentSpecPath) + report.subtasks.map { it.specPath }` with blanks dropped and
    duplicates removed in order.
  - Each entry shows the file name (`substringAfterLast('/')`, also splitting on `\\` for Windows
    paths) as the label and the full path under it.
  - An entry is clickable only when the path is absolute (`File(path).isAbsolute`, or a leading `/`
    or a Windows drive prefix if `java.io.File` is unavailable in that source set). Clickable
    entries call `LocalRepositoryNavigation.current.openFile(path)` with the exact string. Use the
    link styling and `Modifier.clickable` or `TextButton` pattern already used for
    `openRepository` or `openDirectory` links in feature/detail. Give the clickable element a
    `Role.Button` semantic and a content description naming the file.
  - Non-absolute entries render as plain text inside the existing `SelectionContainer`, with no
    click action.
  - Keep the existing feature name and subtask rendering. Do not join paths with any root.
- `feature/detail/.../DetailContent.kt`, "Goal activity" and "Phase activity" panel near lines 360
  to 410:
  - Render `PlanReportSection(it)` for the goal activity entry when the plan report is present. Read
    the report from the same `instance.planReport` field execution history already uses.
    Assumption for implement to confirm: subtask 6 attaches the goal plan-phase report to that same
    work instance field (through `WorkDetailModels.planReport`), so no new domain field is needed.
    If subtask 6 exposes it elsewhere on the goal model, read it from there.
  - Keep the existing `instance.planReport?.let { PlanReportSection(it) }` for standalone plan
    executions in execution history unchanged.
  - Once the report exists, the goal activity entry shows it whatever the later phase state, so it
    stays visible after the plan phase completes.
- Constraint: `PlanReport.kt` is 60 lines today. If the section grows past the module's file size
  norm, extract a `SpecBundleFileList` composable into a sibling file in the same package.

### Task 5. Tests (test obligations)

- `feature/detail/src/jvmTest/.../DetailPlanReportTest.kt`: one test. It provides a recording fake
  `RepositoryNavigation` through `LocalRepositoryNavigation`, renders a `PlanRunReport` whose
  `bundleFiles` holds two absolute paths and one relative path, clicks each absolute entry and
  asserts the recorded `openFile` arguments equal those exact strings. It also asserts the relative
  entry has no click action (`assertHasNoClickAction`). The bug it catches is the desktop joining a
  root onto, rewriting, or dropping runtime paths, or making relative paths clickable.
- No separate test for the empty-`bundleFiles` fallback list or for `NativeServicesImpl.openFile`.
  The fallback is a simple list concatenation and the native launch is OS glue that audit verifies
  by reading.
- `core/data/src/jvmTest/.../WorkStackTest.kt`: update fixtures that build plan reports with
  `.feature-specs/...` relative paths to absolute store paths (for example
  `/home/user/.skill-bill/projects/<id>/specs/SKILL-1-x/spec.md`) only where they assert links or
  path values. Add a `bundleFiles` value to one existing mapping assertion so it proves the mapper
  copies the list verbatim. Do not add a new sibling test.
- Running the tests belongs to the validate phase.

### Constraints

- The desktop never joins a repository root with a plan report path anywhere.
- No runtime-kotlin changes in this subtask. The runtime field and goal attachment come from
  subtask 6.
- No new desktop dependency. `java.awt.Desktop` and the existing `runCommand` are enough.
- No editing of files from the desktop.

## Validation Strategy

The validate phase runs the desktop-tray Gradle checks (`./gradlew check` in `desktop-tray`). This phase does not run them.

## Next Path

```bash
skill-bill goal SKILL-441
```
