# SKILL-440 Subtask 1 - Remove the plugin trees, workflows, and every live reference

Parent spec: `spec.md`

## Scope

Delete the retired IntelliJ plugin and VS Code extension projects, the GitHub workflows that build and release
them, and their `../../../.gitignore` entries. Remove every live pointer at them from docs, agent guidance, orchestration
text, and helper scripts, and replace the README "IDE plugins" paragraph with a short note that the desktop app
replaces them. Stop the runtime-kotlin architecture guards from scanning `intellij-plugin` as a production root.

The tree deletion and the guard edits are one commit: the guard case
`intellij plugin retains its layer package roots` fails as soon as the tree is gone, so neither half stands alone.

This subtask does not touch `../../../install.sh`, `uninstall.sh`, or `scripts/install_smoke_test.sh`; subtask 2 owns the
installer cleanup and the release-tag filter wording.

## Acceptance Criteria

1. The retired plugin projects, their workflows, and their ignore rules are deleted.
   The whole top-level `intellij-plugin/` tree (Gradle project, plugin id `dev.skillbill.status`) and the whole
   top-level `vscode-extension/` tree (npm project, extension id `skill-bill.skill-bill-status`) no longer exist.
   `.github/workflows/plugin-ci.yml`, `.github/workflows/plugin-release.yml`, and
   `.github/workflows/extension-release.yml` no longer exist. The other workflows (`desktop-tray-ci.yml`,
   `desktop-tray-snapshot-cleanup.yml`, `install-smoke-test.yml`, `release.yml`, `validate-agent-configs.yml`) and
   the non-workflow file `../../../.github/workflows/agent/history.md` are unchanged.
   In `.gitignore`, the seven lines at about lines 70-76 are removed: the comment
   `# IntelliJ plugin local build outputs (isolated Gradle project).` and the entries `intellij-plugin/.gradle/`,
   `intellij-plugin/build/`, `intellij-plugin/.intellijPlatform/`, `vscode-extension/node_modules/`,
   `vscode-extension/out/`, and `vscode-extension/*.vsix`; no blank-line pile-up is left where they were.
   There is no root Gradle settings file, so no build-graph edit is needed; if implement finds any `*.kts`,
   `*.gradle`, `*.json`, or `*.yml` file that still includes either tree, it removes that edge here.
   Audit confirms by listing the two directories and three workflow files as absent and reading `.gitignore`.
2. The user-facing docs no longer point at the plugins, and the README says the desktop app replaces them.
   In `../../../README.md`, the whole "**IDE plugins.**" paragraph at about line 236 (it links `intellij-plugin/README.md`
   and `vscode-extension/README.md` and mentions `plugin-v*` / `extension-v*` releases) is replaced by a short note
   with this content: the Skill Bill desktop app replaces the former IntelliJ plugin and VS Code extension; they are
   retired and no longer supported or released; running the installer removes previously installed copies.
   The sentence at about `README.md:470`, "The runtime, desktop app, IntelliJ plugin, and VS Code extension build
   separately.", becomes "The runtime and desktop app build separately."
   In `CONTRIBUTING.md`, the whole `### IntelliJ plugin` section (about lines 28-41, from the heading through the
   line ending `intellij-plugin/ARCHITECTURE.md).`) is deleted, so the preceding content runs straight into
   `## Extension Point: Platform Packs`.
   In `RELEASING.md`, the `## Releasing the IntelliJ plugin` section (starts about line 201) and the
   `## Releasing the VS Code extension` section (starts about line 221, ends about line 234) are deleted, and any
   intro or neighbouring sentence that tells a maintainer to cut `plugin-v*` or `extension-v*` tags or names the
   plugin release workflows is removed or reworded to cover only the runtime and desktop releases.
   In `docs/getting-started.md`, the `intellij-plugin/` and `vscode-extension/` bullets in the repository-layout
   list (about lines 25-26) are deleted, and the plugin gate paragraph (about lines 506-512, starting
   "When changing the IntelliJ plugin, also run its isolated gate (JDK 21):", including its bash block
   `(cd intellij-plugin && ./gradlew check verifyPlugin)` and ending "...live in `intellij-plugin/README.md` and
   `intellij-plugin/ARCHITECTURE.md`.") is deleted.
   Installer behaviour itself is subtask 2's; this criterion only owns the README sentence.
   Audit confirms by reading the four files and finding no mention of the IntelliJ plugin, VS Code extension,
   `plugin-v*`, or `extension-v*` except the README replacement note.
3. Agent guidance, orchestration text, and helper scripts no longer name `intellij-plugin` as an authored-Kotlin
   or scan root. The sentence at about `AGENTS.md:93`, "Authored Kotlin under `runtime-kotlin`, `intellij-plugin`,
   and `../../../runtime-kotlin/build-logic` must contain no // line comments...", names only "`runtime-kotlin` and
   `runtime-kotlin/build-logic`" and stays grammatical. The same root list is fixed the same way in its three
   mirrors: `docs/code-principles.md` about line 292 ("Production and test Kotlin under `runtime-kotlin`,
   `intellij-plugin`, and `runtime-kotlin/build-logic` reference types by imported simple name"),
   `docs/code-principles.md` about line 317 ("Authored Kotlin under `runtime-kotlin`, `intellij-plugin`, and ..."),
   and `orchestration/shell-content-contract/shell-ceremony.md` about line 108; the four sentences keep consistent
   wording.
   In `scripts/skill361_nest.py`, the `REPO / "intellij-plugin"` entry is removed from `SCAN_ROOTS` (about line 15)
   and the `is_dir()`-guarded `plugin_main = REPO / "intellij-plugin/src/main/kotlin"` block (about lines 517-522)
   is deleted; in `scripts/skill361_import_repair.py`, the `REPO / "intellij-plugin"` entry is removed from
   `SCAN_ROOTS` (about line 10). Nothing else in those one-shot scripts changes.
   `design/App/github.md` and every `agent/history.md` / `agent/decisions.md` file stay unchanged by decision
   (historical records; write_history adds the new entry).
   Audit confirms by reading the six files.
4. The runtime-kotlin architecture guards no longer scan `intellij-plugin` as a production root. All files are
   under `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture`.
   `ArchitectureBaselineRecorder.kt` about line 36 reads `productionRoots = listOf("runtime-kotlin"),`.
   The file-level line-ceiling guard `ProductionFileLineCeilingArchitectureTest.kt` about line 11 and the
   logical-type line-ceiling guard `ProductionLogicalTypeLineCeilingArchitectureTest.kt` about line 15 carry the same
   `productionRoots = listOf("runtime-kotlin"),`.
   In `PrincipleEnforcementInventory.kt`, `inlineFqnScanRoots` (about lines 647-652) becomes
   `listOf("runtime-kotlin", "runtime-kotlin/build-logic")`, and `productionPackageSiblingCountSourceRoots` (about
   lines 765-770) drops its trailing ` + "intellij-plugin/src/main/kotlin"` so it is just the existing `.map { ... }`
   expression with an unchanged declared type; `packageClusteringSourceRoots` (about line 774) aliases it and needs
   no edit.
   In `PackageSiblingCountArchitectureTest.kt`, the function `intellij plugin retains its layer package roots`
   (about lines 147-159) is deleted, leaving a single blank line before the next function, and any import that
   becomes unused is removed.
   No `org.jetbrains.kotlin.com.intellij` PSI import anywhere is touched.
   Audit confirms by reading the five files.
5. No tracked file outside the recorded exceptions contains the string `intellij-plugin` or `vscode-extension`.
   This repository-wide sweep catches what criteria 1-4 do not list. Its one known remaining hit is the command
   string at about line 116 of
   `../../../runtime-kotlin/runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/featuretask/FeatureTaskRuntimeReadinessEvidenceSchemaTest.kt`,
   `"(cd intellij-plugin && ./gradlew clean check --no-build-cache)"`, which becomes
   `"(cd desktop-tray && ./gradlew clean check --no-build-cache)"`; its opaque `plugin-ci` / `plugin-ci:check` ids,
   its payload labels, and its name stay unchanged.
   The recorded exceptions are exactly: every `agent/history.md` and `agent/decisions.md` file,
   `design/App/github.md`, everything under `.feature-specs/`, and the retired `plugin-v9.9.9` release scenario in
   `scripts/install_smoke_test.sh` (asset `skill-bill-intellij-plugin-9.9.9.zip`), which subtask 2 keeps.
   Strings that only resemble the plugin are out of scope and stay: the branch name `feat/SKILL-167-plugin-ci`
   in `FileSystemCheckedOutBranchSourceTest.kt` and the `org.jetbrains.kotlin.com.intellij` imports.
   Audit confirms with a read-only search of tracked files for the two strings.

## Criterion Sizing

1. About 0 authored production lines (two whole-tree deletions, three workflow-file deletions, 7 `../../../.gitignore`
   lines removed); the deleted files are many but mechanical. Areas: repo root, `.github/workflows`. Fits: deletion
   is not authored change and needs no per-file reasoning.
2. About 40 changed doc lines, 4 files, 2 areas (repo root docs, `../../../docs`). Fits.
3. About 15 changed lines, 6 files, 3 areas (repo root `../../../AGENTS.md`, `docs/` + `orchestration/`, `scripts/`). Fits.
4. About 25 changed lines, 5 files, 1 area (`../../../runtime-kotlin/runtime-core` repoTest architecture package). Fits.
5. About 1 changed line, 1 file, 1 area (`../../../runtime-kotlin/runtime-infra/contracts`), plus a read-only sweep. Fits.

## Non-Goals

- Installer or uninstaller changes, the release-tag filter, and its smoke scenario (subtask 2).
- Changing the desktop app or the kept workflows.
- Deleting GitHub releases or tags.
- Editing historical `../../../agent/history.md` / `agent/decisions.md` records or `design/App/github.md`.
- Renaming the opaque `plugin-ci` check ids in the readiness-evidence schema case.

## Dependency Notes

Independent. Nothing here needs subtask 2, and subtask 2 needs nothing here. The README note mentions that the
installer removes old copies; both subtasks land on the same branch and PR, so the sentence is accurate when the
branch merges.

## Test Obligations

No new tests. The existing guards and cases must keep their value after the edits:

- The repoTest architecture suite (baseline recorder, both line-ceiling guards, the principle-enforcement inventory
  consumers, and the package sibling-count guard) still passes scanning only `runtime-kotlin` (and
  `../../../runtime-kotlin/build-logic` where listed), and the obsolete `intellij plugin retains its layer package roots`
  case is gone rather than skipped. Realistic bug caught: a guard left pointing at a deleted root, or a guard whose
  scan root list collapsed to empty and silently checks nothing.
- `FeatureTaskRuntimeReadinessEvidenceSchemaTest` `plugin check failure outside the runtime gate cannot validate as
  passing` keeps its assertion with the `desktop-tray` command string. Realistic bug caught: readiness evidence
  accepting a failed non-runtime gate as passing.

## Validation Strategy

The validate phase runs `./gradlew check` from `runtime-kotlin` (repoTest architecture suite and the contracts unit
tests included) and the agent-config validation the gate covers, which reads `../../../AGENTS.md` and `orchestration/`. No
other phase compiles or runs tests.

## Next Path

```bash
skill-bill goal SKILL-440
```

## Implementation Details

All line numbers come from the preplan digest and are approximate; read each target region before editing it. Do not
run `./gradlew`, any test, the smoke test, or any install/uninstall command in this subtask; the validate phase owns
all of that. Tasks 1, 2, and 3 must land in the same commit (see Scope).

### Task 1 - Delete the plugin trees, their workflows, and their ignore rules (AC1)

- Remove the whole `intellij-plugin/` and `vscode-extension/` trees with `git rm -r` so every tracked file goes, then
  confirm no untracked leftovers (build outputs such as `intellij-plugin/build/`, `.gradle/`, `.intellijPlatform/`,
  `vscode-extension/node_modules/`, `out/`, `*.vsix`) keep either directory alive on disk. Delete those too; they were
  only ignored build outputs.
- Optional before deleting: read `intellij-plugin/src/main/resources/META-INF/plugin.xml` to confirm the plugin id
  `dev.skillbill.status` and note it in the phase output for subtask 2. Do not block on it; the intake already gives
  the id.
- `git rm` `.github/workflows/plugin-ci.yml`, `.github/workflows/plugin-release.yml`,
  `.github/workflows/extension-release.yml`. Leave `desktop-tray-ci.yml`, `desktop-tray-snapshot-cleanup.yml`,
  `install-smoke-test.yml`, `release.yml`, `validate-agent-configs.yml`, and `../../../.github/workflows/agent/history.md`
  untouched.
- `../../../.gitignore` lines ~70-76: delete the comment `# IntelliJ plugin local build outputs (isolated Gradle project).`
  and the six entries under it. If the block was separated from its neighbours by blank lines on both sides, also drop
  one of those blank lines so exactly one blank line separates the surrounding blocks.
- Build-graph check: the digest found no root `settings.gradle(.kts)` and no build file that includes either tree.
  Task 6's sweep re-confirms across `*.kts`, `*.gradle`, `*.json`, `*.yml`; remove any edge it finds.

### Task 2 - Stop the architecture guards scanning `intellij-plugin` (AC4)

All under `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture`:

- `ArchitectureBaselineRecorder.kt` ~36: `productionRoots = listOf("runtime-kotlin", "intellij-plugin"),` becomes
  `productionRoots = listOf("runtime-kotlin"),`.
- `ProductionFileLineCeilingArchitectureTest.kt` ~11 and `ProductionLogicalTypeLineCeilingArchitectureTest.kt` ~15:
  the same one-line edit.
- `PrincipleEnforcementInventory.kt`:
  - `inlineFqnScanRoots` (~647-652): remove the `"intellij-plugin",` element so the value is
    `listOf("runtime-kotlin", "runtime-kotlin/build-logic")`. Keep the existing one-per-line or single-line layout,
    whichever the file uses, and keep a trailing comma only if the file's style already uses one.
  - `productionPackageSiblingCountSourceRoots` (~765-770): drop the trailing ` + "intellij-plugin/src/main/kotlin"`
    so the initializer is just the existing `.map { ... }` expression. The declared type stays `List<String>`.
  - `packageClusteringSourceRoots` (~774) aliases the above; leave it.
- `PackageSiblingCountArchitectureTest.kt` ~147-159: delete the whole `@Test fun \`intellij plugin retains its layer
  package roots\`()` including its annotation, leaving exactly one blank line before the following test
  (`task-runtime and application review models stay data-only...`). Then check the file's imports: remove any import
  that only the deleted test used. `ArchitectureScanSupport` is still used by the neighbouring tests per the digest,
  so expect no import change; confirm by reading.
- Do not touch any `org.jetbrains.kotlin.com.intellij` PSI import in these or other files.
- Repo rule: authored Kotlin carries no `//` line comments; add none.

### Task 3 - Retarget the readiness-evidence schema case (AC5)

- `../../../runtime-kotlin/runtime-infra/contracts/src/test/kotlin/skillbill/infrastructure/contracts/workflow/featuretask/FeatureTaskRuntimeReadinessEvidenceSchemaTest.kt`
  ~116: change `"(cd intellij-plugin && ./gradlew clean check --no-build-cache)"` to
  `"(cd desktop-tray && ./gradlew clean check --no-build-cache)"`. Change nothing else: the `plugin-ci` /
  `plugin-ci:check` ids (~46, 107, 111), the `plugin-failure` payload labels, and the test name
  `plugin check failure outside the runtime gate cannot validate as passing` stay as they are (Non-Goals).

### Task 4 - Rewrite the user-facing docs (AC2)

- `../../../README.md` ~236: replace the whole "**IDE plugins.**" paragraph with a short note in the README's bold-lead style,
  for example: "**IDE plugins (retired).** The Skill Bill desktop app replaces the former IntelliJ plugin and VS Code
  extension. They are no longer supported or released, and running the installer removes previously installed
  copies." Do not mention `plugin-v*`, `extension-v*`, or link the deleted READMEs. Keep surrounding blank lines as
  they were.
- `../../../README.md` ~470: "The runtime, desktop app, IntelliJ plugin, and VS Code extension build separately." becomes
  "The runtime and desktop app build separately." Leave the rest of that paragraph unchanged.
- `../../../CONTRIBUTING.md` ~28-41: delete from the `### IntelliJ plugin` heading through the line ending
  `intellij-plugin/ARCHITECTURE.md).`, so the preceding content is followed by one blank line and then
  `## Extension Point: Platform Packs`.
- `../../../RELEASING.md`: delete `## Releasing the IntelliJ plugin` (~201) through the end of `## Releasing the VS Code
  extension` (~234, up to the next `##` heading or end of file). Then search the file for `plugin-v`, `extension-v`,
  `plugin-release`, `extension-release`, `plugin`, `VS Code`, and `IntelliJ`, and remove or reword any intro,
  table-of-contents entry, or neighbouring sentence that describes the plugin tag streams or workflows so it covers
  only the runtime and desktop releases. Assumption for implement to confirm: the digest did not show such an intro
  sentence; if none exists, no further edit is needed.
- `../../../docs/getting-started.md`: delete the `intellij-plugin/` and `vscode-extension/` bullets (~25-26) from the
  repository-layout list without disturbing the neighbouring bullets' indentation, and delete the plugin gate
  paragraph (~506-512) from "When changing the IntelliJ plugin, also run its isolated gate (JDK 21):" through the
  bash block and the sentence ending "...live in `intellij-plugin/README.md` and `intellij-plugin/ARCHITECTURE.md`.",
  leaving one blank line between the surrounding paragraphs. Then search the file for `plugin`, `IntelliJ`, and
  `VS Code` and fix any remaining reference to the retired projects (leave unrelated uses such as Gradle plugins).

### Task 5 - Fix agent guidance, orchestration text, and helper scripts (AC3)

- `../../../AGENTS.md` ~93: "Authored Kotlin under `runtime-kotlin`, `intellij-plugin`, and `runtime-kotlin/build-logic` ..."
  becomes "Authored Kotlin under `runtime-kotlin` and `runtime-kotlin/build-logic` ..." with the rest of the sentence
  unchanged.
- `../../../docs/code-principles.md` ~292: "Production and test Kotlin under `runtime-kotlin`, `intellij-plugin`, and
  `runtime-kotlin/build-logic` reference types ..." becomes "Production and test Kotlin under `runtime-kotlin` and
  `runtime-kotlin/build-logic` reference types ...".
- `../../../docs/code-principles.md` ~317: same root-list edit as `AGENTS.md`.
- `../../../orchestration/shell-content-contract/shell-ceremony.md` ~108: same root-list edit. Assumption for implement to
  confirm: the digest says the sentence mirrors `AGENTS.md:93` but did not quote it; apply the identical
  "`runtime-kotlin` and `runtime-kotlin/build-logic`" pattern to whatever root list it holds and keep the rest of the
  sentence verbatim.
- `../../../scripts/skill361_nest.py`: remove the `REPO / "intellij-plugin"` element from `SCAN_ROOTS` (~15) and delete the
  `is_dir()`-guarded block that defines and uses `plugin_main = REPO / "intellij-plugin/src/main/kotlin"` (~517-522),
  keeping the surrounding function's control flow and indentation valid (if the block's removal leaves an empty
  body, the enclosing structure must still parse; confirm by reading). Change nothing else, including the hardcoded
  `/home/sermilion/...` REPO path.
- `../../../scripts/skill361_import_repair.py` ~10: remove the `REPO / "intellij-plugin"` element from `SCAN_ROOTS`; nothing
  else.
- Leave `../../../design/App/github.md` and every `agent/history.md` / `agent/decisions.md` file unchanged.

### Task 6 - Repository-wide sweep (AC5, AC1 build-graph check)

- Run a read-only search of tracked files (`git grep -n -e 'intellij-plugin' -e 'vscode-extension'`) and of
  `*.kts`, `*.gradle`, `*.json`, `*.yml`, `*.sh`, `*.py` for both strings. The only allowed hits are the recorded
  exceptions: every `../../../agent/history.md` and `agent/decisions.md`, `design/App/github.md`, everything under
  `.feature-specs/`, and the `plugin-v9.9.9` / `skill-bill-intellij-plugin-9.9.9.zip` scenario in
  `scripts/install_smoke_test.sh` (owned and kept by subtask 2; do not edit that file).
- Fix any other hit in the spirit of criteria 1-4 (drop the root, the link, or the sentence). If a hit is in
  `../../../install.sh`, `uninstall.sh`, or `scripts/install_smoke_test.sh`, leave it for subtask 2 and report it.
- Also search the edited docs for `IntelliJ plugin`, `VS Code extension`, `plugin-v`, `extension-v`; only the README
  replacement note may name the retired projects.
- Leave alone strings that merely resemble the plugin: `feat/SKILL-167-plugin-ci` in
  `FileSystemCheckedOutBranchSourceTest.kt` and all `org.jetbrains.kotlin.com.intellij` imports.

### Tests

No new tests (see Test Obligations). Existing coverage that must keep its value, run only by the validate phase via
`./gradlew check` from `runtime-kotlin`: the repoTest architecture suite (baseline recorder, both line-ceiling
guards, inventory consumers, package sibling-count guard, with the obsolete layer-roots case deleted, not skipped)
and the contracts unit case `plugin check failure outside the runtime gate cannot validate as passing` with the
`desktop-tray` command string. The agent-config validation in the same gate reads `../../../AGENTS.md` and `orchestration/`.

### Constraints

- Never edit the parent spec, the sibling sub-spec, `../../../install.sh`, `uninstall.sh`, or `scripts/install_smoke_test.sh`.
- Do not change the desktop app, the kept workflows, or historical `../../../agent` records.
- No compile, build, test, check, or install command in this subtask.
