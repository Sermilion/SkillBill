# SKILL-440 - retire-the-intellij-plugin-and-vs-code-extension-the-desktop-app-desktop-tray-replaces-them-and-they-are-no-longer-supported

## Mode

decomposed

## Intended Outcome

SKILL-440 Retire the IntelliJ plugin and VS Code extension; the desktop app (desktop-tray) replaces them and they are no longer supported.

Scope (operator intake, preserved verbatim):
1. Delete the top-level `intellij-plugin/` (Gradle project, plugin id `dev.skillbill.status`) and `vscode-extension/` (npm project, extension id `skill-bill.skill-bill-status`) trees, the `.github/workflows/plugin-ci.yml`, `plugin-release.yml`, and `extension-release.yml` workflows, the `.gitignore` entries for their build outputs, and every doc reference: README.md (~line 236 "IDE plugins" paragraph and ~line 470), CONTRIBUTING.md "IntelliJ plugin" section, RELEASING.md "Releasing the IntelliJ plugin" and "Releasing the VS Code extension" sections, docs/getting-started.md (~lines 25-26, 506-512), AGENTS.md (~line 93 authored-Kotlin roots), and any skill or orchestration text that points at them.
2. Remove `intellij-plugin` from runtime-kotlin repoTest architecture guards that scan it as a production root: ArchitectureBaselineRecorder.kt, ProductionFileLineCeilingArchitectureTest.kt, ProductionLogicalTypeLineCeilingArchitectureTest.kt, PrincipleEnforcementInventory.kt (~lines 650, 770), PackageSiblingCountArchitectureTest.kt (`intellij plugin retains its layer package roots` test), and FeatureTaskRuntimeReadinessEvidenceSchemaTest.kt (~line 116). Do not touch the `org.jetbrains.kotlin.com.intellij` PSI imports, those are Kotlin compiler APIs unrelated to the plugin.
3. Keep install.sh's runtime-release resolution that skips `plugin-v*` / `extension-v*` releases (resolve_latest_runtime_release_tag, the latest-release redirect comment near line 460, and the install_smoke_test.sh fixture near line 444): old plugin releases stay on GitHub, so the filter is still needed. Only update wording if it implies the plugins are still shipped.
4. Installer cleanup of previously installed copies, run on every `install.sh` run (alongside the existing legacy-cleanup steps such as migrate_legacy_config_to_durable_path and the legacy desktop app path handling), non-interactive and idempotent:
   - IntelliJ-family IDEs: scan user plugin directories for every JetBrains product/version (macOS `~/Library/Application Support/JetBrains/*/plugins/`, Linux `${XDG_DATA_HOME:-~/.local/share}/JetBrains/*/` and `~/.config/JetBrains/*/plugins/` legacy layout, plus Android Studio under `Google/AndroidStudio*`). Identify the Skill Bill plugin by plugin id `dev.skillbill.status` in the plugin jar's `META-INF/plugin.xml`, not by directory name alone (the release zip's root directory name is not a stable identifier). Remove only matching plugin directories.
   - VS Code family: remove extensions with id `skill-bill.skill-bill-status` from `~/.vscode/extensions`, `~/.vscode-insiders/extensions`, `~/.vscode-oss/extensions` (VSCodium), and `~/.cursor/extensions`. Prefer the editor CLI (`code --uninstall-extension skill-bill.skill-bill-status`, etc.) when that CLI is on PATH so the editor's extensions.json stays consistent; fall back to deleting the `skill-bill.skill-bill-status-*` directory when the CLI is absent.
   - Report each removal (IDE + path) with ok/info lines; print nothing beyond one info line when nothing was found. Never fail the install because cleanup failed: warn and continue. If an IDE is running, still remove and tell the user to restart that IDE.
   - Decide and record whether uninstall.sh should also run the same cleanup (recommended: yes, shared helper).
   - Platform scope: macOS and Linux (the hosts install.sh supports).
5. Tell users the desktop app replaces the plugins: a one-line installer notice when a plugin copy was removed, and a short README note.

Non-goals: changing the desktop app, deleting existing GitHub plugin/extension releases or tags, removing the marketplace listings (none published; distribution was VSIX/zip from GitHub releases).

## Overview

The desktop app (`desktop-tray`) replaces the IntelliJ plugin and VS Code extension. This goal removes both
plugin projects and every live pointer at them, stops the runtime-kotlin architecture guards from scanning the
deleted plugin tree, and teaches `install.sh` (and `uninstall.sh`) to remove previously installed copies from
users' IDEs while keeping the release-tag filter that skips the retired `plugin-v*` / `extension-v*` releases
that stay on GitHub.

The work splits into two independent subtasks, each one commit on the same feature branch:

1. `spec_subtask_1_remove-plugin-trees-and-references.md`: delete the plugin trees, their workflows and
   `.gitignore` entries, every doc/guidance/script reference, and the runtime-kotlin guard scan roots that name
   the deleted tree. The tree deletion and the guard edits land together because the guard case
   `intellij plugin retains its layer package roots` fails the moment the tree is gone.
2. `spec_subtask_2_installer-retired-plugin-cleanup.md`: the IDE-plugin cleanup in `install.sh` and
   `uninstall.sh`, its reporting and the desktop-replaces-plugins notice, and the release-tag filter wording.

Neither subtask needs the other to compile, pass checks, or make sense; each applies its rule to the tree as it is.

## Decisions

- Uninstall also cleans up: `uninstall.sh` runs the same cleanup. It is skipped when `uninstall.sh` runs as
  install's pre-install wipe (`SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1`), because `install.sh` runs its own copy on
  every install and a second pass would print twice.
- Duplicate, not sourced: the cleanup is a self-contained function block duplicated in `install.sh` and
  `uninstall.sh` (the same pattern as the already duplicated `info`/`ok`/`warn`/`err` helpers). This deviates from
  the intake's "shared helper" suggestion on purpose: the release skills bundle
  (`.github/workflows/release.yml:137`) ships only `uninstall.sh` beside the skills, `scripts/` is not shipped, and
  `install.sh` must work when piped from stdin before any bundle is extracted, so a sourced helper file would not be
  reachable on every install path.
- `design/App/github.md` (a design provenance note) and every `agent/history.md` / `agent/decisions.md` file stay
  unchanged: they are historical records, and the write_history phase adds the new entry.
- `FeatureTaskRuntimeReadinessEvidenceSchemaTest` keeps its behaviour (a failed non-runtime gate blocks
  readiness); only its command string changes from the deleted tree to `desktop-tray`. Its opaque `plugin-ci`
  check ids stay.
- The one-shot SKILL-361 migration scripts `scripts/skill361_nest.py` and `scripts/skill361_import_repair.py` drop
  their `intellij-plugin` scan root.

## Acceptance Criteria

1. The `intellij-plugin/` and `vscode-extension/` trees, the `plugin-ci.yml`, `plugin-release.yml`, and
   `extension-release.yml` workflows, and the seven `.gitignore` lines for their build outputs are gone from the
   repository. Owned by subtask 1.
2. No live doc, agent guidance, orchestration text, helper script, or runtime-kotlin architecture guard scan
   root names `intellij-plugin` or `vscode-extension`, and the README states that the desktop app replaces the
   retired plugins. Historical records (`agent/history.md`, `agent/decisions.md`, `design/App/github.md`,
   `.feature-specs/`) and the installer's retired-release scenario are the only exceptions. Owned by subtask 1.
3. `install.sh` removes previously installed copies of the IntelliJ plugin (identified by plugin id
   `dev.skillbill.status` in the plugin jar's `META-INF/plugin.xml`, across macOS and Linux JetBrains layouts and
   Android Studio) and of the VS Code extension `skill-bill.skill-bill-status` (VS Code, Insiders, VSCodium,
   Cursor; editor CLI first, directory deletion as fallback) on every run. Owned by subtask 2.
4. The cleanup reports each removal, prints exactly one info line when nothing was found, prints the restart hint
   and the desktop-replaces-plugins notice when something was removed, never fails the install, and also runs
   from `uninstall.sh`. Owned by subtask 2.
5. `install.sh` still skips the retired `plugin-v*` / `extension-v*` GitHub releases when resolving the latest
   runtime release, and its comments describe those releases as retired rather than shipped. Owned by subtask 2.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection.
- Do not touch the `org.jetbrains.kotlin.com.intellij` PSI imports; they are Kotlin compiler APIs.
- `install.sh` and `uninstall.sh` must run under macOS `/bin/bash` 3.2 and Linux bash 5 with `set -euo pipefail`.

## Non-Goals

- Changing the desktop app.
- Deleting existing GitHub plugin/extension releases or tags.
- Removing marketplace listings (none were published).
- Editing historical `agent/history.md` / `agent/decisions.md` records or `design/App/github.md`.

## Validation Strategy

The validate phase runs the repository gate (`./gradlew check` from `runtime-kotlin`, which includes the
repoTest architecture suite and the contracts unit tests) plus the agent-config validation the gate covers, and
the installer shell smoke suite `scripts/install_smoke_test.sh`. No earlier phase compiles, builds, or runs tests.
