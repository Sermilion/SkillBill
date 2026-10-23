# SKILL-440 Subtask 2 - Installer and uninstaller remove retired IDE plugin copies

Parent spec: `spec.md`

## Scope

Add a non-interactive, idempotent cleanup that removes previously installed copies of the retired Skill Bill
IntelliJ plugin (plugin id `dev.skillbill.status`) and VS Code extension (extension id
`skill-bill.skill-bill-status`) from the user's IDEs. `../../../install.sh` runs it on every install; `uninstall.sh` runs the
same cleanup. Keep the runtime-release resolver's skip of the retired `plugin-v*` / `extension-v*` GitHub releases
and make its comments say those releases are retired.

Shared facts every criterion relies on:

- `../../../install.sh` is bash with `set -euo pipefail`; its output helpers are `info()` (▸), `ok()` (✓), `warn()` (⚠),
  and `err()` (✗) at about lines 69-72; `uninstall.sh` duplicates the same four at about lines 46-49.
- Both scripts must run on macOS `/bin/bash` 3.2 and Linux bash 5: no `mapfile`/`readarray`, no associative
  arrays, no `${var,,}`; empty arrays expand as `${arr[@]+"${arr[@]}"}`; unmatched globs are guarded with
  `[[ -e "$path" ]] || continue`.
- Platform scope is macOS (`uname -s` = `Darwin`) and Linux (`Linux`); on any other OS the cleanup returns 0 and
  prints nothing.
- The cleanup reads only `$HOME`, `${XDG_DATA_HOME:-$HOME/.local/share}`, and `PATH`, so a fake `HOME` drives it.
- Decision (recorded here and in the parent spec): the cleanup is one self-contained function block, named
  `cleanup_retired_ide_plugins` with any private helpers prefixed `retired_ide_plugin_`, duplicated byte-for-byte in
  `../../../install.sh` and `uninstall.sh`. It is not a sourced shared file, because the release skills bundle
  (`.github/workflows/release.yml:137`) ships only `uninstall.sh` beside the skills, `scripts/` is not shipped, and
  `install.sh` must work when piped from stdin before any bundle exists. This deviates from the intake's
  "shared helper" suggestion for that reason.

## Acceptance Criteria

1. `../../../install.sh` defines `cleanup_retired_ide_plugins`, which removes IntelliJ-family copies of the plugin
   identified by plugin id, never by directory name.
   It scans these plugin roots, each only when it exists: on macOS,
   `$HOME/Library/Application Support/JetBrains/*/plugins` and
   `$HOME/Library/Application Support/Google/AndroidStudio*/plugins`; on Linux, the modern layout where the product
   directory itself holds plugin directories, `${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains/*` and
   `${XDG_DATA_HOME:-$HOME/.local/share}/Google/AndroidStudio*`, plus the legacy layout
   `$HOME/.config/JetBrains/*/plugins` and `$HOME/.config/Google/AndroidStudio*/plugins`.
   For each direct child of a plugin root: a directory is a candidate when any `lib/*.jar` inside it carries the id;
   a regular `*.jar` file directly in the root is a candidate when that jar carries the id. A jar carries the id when
   `xml="$(unzip -p "$jar" META-INF/plugin.xml 2>/dev/null || true)"` contains `<id>dev.skillbill.status</id>`
   (captured into a variable first, never piped into `grep -q`, to avoid SIGPIPE under `pipefail`).
   Only a matching candidate is removed (`rm -rf` on that exact directory, `rm -f` on that exact jar), and only after
   checking that its parent is the scanned plugin root; a directory named like the plugin whose jars carry another
   id, and every non-matching sibling, stay. If `unzip` is not on `PATH` and at least one plugin root exists, the
   function prints one `warn` that IntelliJ plugin cleanup was skipped because `unzip` is missing, skips the
   IntelliJ scan, and continues with the VS Code scan.
   The IDE label used in reporting is the product/version directory name (for example `IntelliJIdea2024.3` or
   `AndroidStudio2024.2`). Reporting and the call site belong to criterion 3; VS Code to criterion 2.
   Audit confirms by reading the function in `install.sh`.
2. `cleanup_retired_ide_plugins` in `../../../install.sh` also removes VS Code-family copies of the extension
   `skill-bill.skill-bill-status`. It checks exactly four extension roots, each paired with its editor label and CLI:
   `$HOME/.vscode/extensions` (VS Code, `code`), `$HOME/.vscode-insiders/extensions` (VS Code Insiders,
   `code-insiders`), `$HOME/.vscode-oss/extensions` (VSCodium, `codium`), and `$HOME/.cursor/extensions` (Cursor,
   `cursor`). Matching entries are the directories `skill-bill.skill-bill-status-*` directly in a root (VS Code
   names them `<publisher>.<name>-<version>` with an optional platform suffix).
   The CLI runs only for a root that has at least one match: when the paired CLI is on `PATH` (`command -v`), the
   function runs `<cli> --uninstall-extension skill-bill.skill-bill-status` with its output discarded and its
   failure tolerated. Afterwards, every matching directory that still exists in that root (the CLI was absent,
   failed, or only marked it obsolete) is deleted with `rm -rf`. A root with no match never invokes its CLI.
   Unrelated extension directories in the same root stay.
   Audit confirms by reading the function in `install.sh`.
3. The cleanup reports, tolerates failure, and runs on every install.
   For each removed item it prints `ok "  removed IntelliJ plugin (<IDE label>): <path>"` or
   `ok "  removed VS Code extension (<editor label>): <path>"`. After all removals it prints, once per distinct IDE
   or editor label that had a removal, `info "Restart <label> if it is running to finish removing the Skill Bill
   plugin."`, and when at least one item was removed it prints exactly one
   `info "The Skill Bill desktop app replaces the IntelliJ plugin and VS Code extension; they are no longer supported."`.
   When nothing was found it prints exactly one line, `info "No retired Skill Bill IDE plugins found."`, and nothing
   else (the missing-`unzip` warning from criterion 1 is the only other line it may print).
   Running IDEs are not detected and never block removal. Every removal and CLI call is guarded (`|| ...` or inside
   an `if`) so `set -e` cannot abort the script; a failed removal prints `warn "Could not remove <path>"` and the
   function continues; the function always returns 0 and never calls `exit`.
   In `run_full_install` (about line 2477), `cleanup_retired_ide_plugins` is called directly after
   `migrate_legacy_config_to_durable_path` (about line 2483). Assumption for implement to confirm: `run_full_install`
   is the only entry flow that completes an install; if `../../../install.sh` has another flow that installs without passing
   through it, that flow calls the cleanup too.
   Audit confirms by reading the function body and the call site.
4. `../../../uninstall.sh` runs the same cleanup. It contains a byte-identical copy of the `cleanup_retired_ide_plugins`
   block from `install.sh` (the function plus its `retired_ide_plugin_` helpers, using the `info`/`ok`/`warn`
   helpers `uninstall.sh` already defines), and its top-level flow calls it after `remove_runtime_launchers` (about
   line 755) and before the "Uninstall complete" summary. The call is skipped when `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE`
   equals `1`, which is how `install.sh`'s `run_pre_install_uninstall` (about lines 741-763) invokes `uninstall.sh`,
   because `install.sh` runs its own cleanup on every install and a second pass would print twice. Assumption for
   implement to confirm: `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE=1` is set only by that pre-install call; if another
   caller sets it, gate on a dedicated variable that only `run_pre_install_uninstall` passes instead.
   Removed plugin paths are reported by the cleanup's own `ok` lines and are not added to `REMOVED_TARGETS`, so the
   "Removed installs" count keeps meaning agent installs.
   Audit confirms by comparing the two blocks and reading the `uninstall.sh` call site.
5. `../../../install.sh` still skips the retired plugin and extension releases when resolving the runtime release, and its
   comments call them retired. The tag-matching logic of `resolve_latest_runtime_release_tag` (about lines 197-228),
   which accepts only `v?X.Y.Z...` tags and so skips `plugin-v*` and `extension-v*`, is unchanged, as is the
   latest-release handling near line 460.
   The comment at about lines 195-196 ("The API lists recent releases first, so skip plugin releases and use the
   first versioned runtime tag.") and the comment at about lines 460-461 ("The latest-release redirect would be won
   by a plugin-v* release...") are reworded to say that retired `plugin-v*` / `extension-v*` releases remain on
   GitHub, so they are skipped. No other wording in `install.sh` implies the plugins still ship.
   The retired-release smoke scenario is kept (see Test Obligations).
   Audit confirms by reading those two regions of `install.sh`.

## Criterion Sizing

1. About 90 production shell lines, 1 file, 1 area (`../../../install.sh`). Fits.
2. About 50 production shell lines, 1 file, 1 area (`../../../install.sh`). Fits.
3. About 40 production shell lines, 1 file, 1 area (`../../../install.sh`). Fits.
4. About 185 production shell lines (the duplicated block plus the gated call), 1 file, 1 area (`../../../uninstall.sh`).
   Fits.
5. About 4 changed comment lines, 1 file, 1 area (`../../../install.sh`). Fits.

## Non-Goals

- Deleting the plugin trees, workflows, or doc references (subtask 1).
- Adding a new shipped helper file or changing `../../../.github/workflows/release.yml`.
- Detecting running IDEs, prompting the user, or editing VS Code's `extensions.json` by hand.
- Windows support.
- Changing the release-tag regex or deleting GitHub plugin/extension releases or tags.

## Dependency Notes

Independent. The cleanup targets copies installed on users' machines and works whether or not subtask 1 has
deleted the plugin source trees. Subtask 1's README note says the installer removes old copies; both subtasks land on
the same branch and PR.

## Test Obligations

All in `../../../scripts/install_smoke_test.sh`, which already provides a fake `HOME` (`FAKE_HOME`, about line 7), PATH shim
directories, `pass`/`fail` (about lines 19-20), `assert_file_exists`/`assert_file_absent` (about lines 22-32), and
drives `install.sh` from stdin with `HOME=$fake_home PATH="$work:$PATH"` (about lines 400-413). Fold the seeds into
as few existing install scenarios as possible rather than adding a full install run per obligation.

1. IntelliJ identification by id (criterion 1): seed, under the layout for the host's `uname -s`, a plugin
   directory with a non-obvious name (for example `renamed-root/lib/status.jar`) whose jar holds
   `META-INF/plugin.xml` with `<id>dev.skillbill.status</id>`, a sibling directory named `skill-bill-status` whose jar
   carries another id, and an unrelated plugin. Assert only the first is removed and its `ok` line names the product
   directory. Realistic bug caught: matching on directory name or over-deleting siblings.
2. VS Code CLI and fallback (criterion 2): seed `skill-bill.skill-bill-status-0.1.0` under fake
   `~/.vscode/extensions` and `~/.cursor/extensions` plus an unrelated extension; put a stub `code` on `PATH` that
   records its arguments and removes nothing, and no `cursor`. Assert the stub received
   `--uninstall-extension skill-bill.skill-bill-status`, both matching directories are gone, and the unrelated one
   stays. Realistic bug caught: CLI path taken but leftover directory never removed, or the fallback skipped.
3. Idempotent no-op (criterion 3): a run with nothing to remove prints exactly one
   `No retired Skill Bill IDE plugins found.` line and no removal or notice lines. Realistic bug caught: noisy or
   duplicated output on clean machines.
4. Failure tolerance (criterion 3): a matching plugin directory whose parent is made read-only makes the install
   print the `Could not remove` warning and still exit 0. Skip this check when running as root, where `chmod` does
   not block removal. Realistic bug caught: `set -e` aborting the install on a cleanup failure.
5. Copy parity (criterion 4): the `cleanup_retired_ide_plugins` block extracted from `../../../install.sh` and from
   `uninstall.sh` is identical. Realistic bug caught: one copy fixed and the other left to drift.
6. Keep the existing retired-release scenario unchanged in behaviour (criterion 5): the `plugin-v9.9.9` release with
   asset `skill-bill-intellij-plugin-9.9.9.zip` (about lines 436-445) and its assertions (about lines 881-895) stay;
   only the comment at about lines 415-416 is reworded to say a retired `plugin-v*` release remains on GitHub.

Fixture jars are built at test time with `zip` (present on macOS and on GitHub-hosted Ubuntu runners). Assumption for
implement to confirm by reading `../../../.github/workflows/install-smoke-test.yml`: the smoke workflow runs on a
GitHub-hosted image with `zip` and `unzip`; if not, build the jar with `python3 -I -c` and `zipfile` instead.

## Validation Strategy

The validate phase runs `../../../scripts/install_smoke_test.sh` (fake `HOME`, no real install) together with the repository
gate. Implement and audit read the scripts; they never run them.

## Next Path

```bash
skill-bill goal SKILL-440
```

## Implementation Details

All facts come from the preplan digest. Line numbers are approximate; implement locates each anchor by name before
editing. Implement and audit read the scripts and never run them, `./gradlew`, or the smoke test. Validate runs them.

### Task 1 - Write the cleanup block in `../../../install.sh` (criteria 1, 2, 3)

Path: `../../../install.sh`, placed right after `migrate_legacy_config_to_durable_path` (defined about lines 716-731), so the
legacy-cleanup helpers stay together.

Delimit the block with two marker lines so the parity test can extract it:

```bash
# >>> retired IDE plugin cleanup (keep identical in install.sh and uninstall.sh)
...
# <<< retired IDE plugin cleanup
```

Everything between the markers is copied byte-for-byte into `../../../uninstall.sh` in Task 4. It may reference only `info`,
`ok`, `warn`, `$HOME`, `XDG_DATA_HOME`, `PATH`, and standard tools (`uname`, `unzip`, `rm`, `dirname`, `basename`,
`command`). Do not use `host_os()`: it exists only in `install.sh`, so call `uname -s` directly
(`os="$(uname -s 2>/dev/null || true)"`).

Functions, in this order:

1. `retired_ide_plugin_jar_has_id <jar>`: `[[ -f "$jar" ]] || return 1`, then
   `xml="$(unzip -p "$jar" META-INF/plugin.xml 2>/dev/null || true)"` and
   `[[ "$xml" == *"<id>dev.skillbill.status</id>"* ]]`. Never pipe into `grep -q`.
2. `retired_ide_plugin_dir_has_id <dir>`: loop `for jar in "$dir"/lib/*.jar`, `[[ -f "$jar" ]] || continue`, and
   return 0 on the first jar that has the id. Otherwise return 1.
3. `retired_ide_plugin_note_removal <label>`: increments the removed counter and appends `label` to the
   restart-label list when it is not already there. Use a plain loop compare, because bash 3.2 has no associative
   arrays.
4. `retired_ide_plugin_remove <kind> <label> <path>`: `kind` is `IntelliJ plugin` or `VS Code extension`. It
   increments the found counter, then runs `rm -rf -- "$path"` for a directory or `rm -f -- "$path"` for a file,
   inside an `if`. On success, when `[[ ! -e "$path" ]]`, it prints `ok "  removed ${kind} (${label}): ${path}"` and
   calls `retired_ide_plugin_note_removal "$label"`. Otherwise it prints `warn "Could not remove ${path}"`. It
   always returns 0.
5. `retired_ide_plugin_scan_root <root> <label>`: loop `for candidate in "$root"/*`, `[[ -e "$candidate" ]] ||
   continue`, and check `[[ "$(dirname "$candidate")" == "$root" ]] || continue` (roots are built without a trailing
   slash). A directory is a match when `retired_ide_plugin_dir_has_id` says so. A regular file is a match when its
   name ends in `.jar` and `retired_ide_plugin_jar_has_id` says so. A match is passed to
   `retired_ide_plugin_remove "IntelliJ plugin" "$label" "$candidate"`. Anything else is left alone.
6. `retired_ide_plugin_scan_vscode <root> <label> <cli>`: return 0 unless `[[ -d "$root" ]]`. Collect matches with
   `for entry in "$root"/skill-bill.skill-bill-status-*`, `[[ -d "$entry" ]] || continue`, into a local indexed array.
   With zero matches, return 0 without running the CLI. Otherwise, when `command -v "$cli" >/dev/null 2>&1`, run
   `"$cli" --uninstall-extension skill-bill.skill-bill-status >/dev/null 2>&1 </dev/null || true`. The `</dev/null`
   stops a headless CLI from waiting on stdin. Then, for each collected match: if it no longer exists (the CLI
   removed it), increment the found counter, print the `ok "  removed VS Code extension (${label}): ${entry}"` line,
   and record the removal. If it still exists, call `retired_ide_plugin_remove "VS Code extension" "$label"
   "$entry"`. Expand the array with `${matches[@]+"${matches[@]}"}`.
7. `cleanup_retired_ide_plugins`: the entry point.
   - Declare state as `local` here: `retired_found=0`, `retired_removed=0`, `local -a retired_labels=()`. The
     helpers read and update them through bash dynamic scoping. Every helper's own loop variables are `local`.
   - Run `case "$os" in Darwin|Linux) ;; *) return 0 ;; esac`.
   - Build the IntelliJ root list as two parallel indexed arrays, roots and labels. Add an entry only when
     `[[ -d "$root" ]]`, and quote the fixed prefix while leaving the glob unquoted:
     - Darwin: `"$HOME/Library/Application Support/JetBrains/"*/plugins` and
       `"$HOME/Library/Application Support/Google/"AndroidStudio*/plugins`. The label is
       `basename "$(dirname "$root")"`.
     - Linux, modern layout: `"$data_home/JetBrains/"*` and `"$data_home/Google/"AndroidStudio*`, with
       `data_home="${XDG_DATA_HOME:-$HOME/.local/share}"`. The label is `basename "$root"`.
     - Linux, legacy layout: `"$HOME/.config/JetBrains/"*/plugins` and `"$HOME/.config/Google/"AndroidStudio*/plugins`.
       The label is `basename "$(dirname "$root")"`.
     - Do not use `shopt -s nullglob`, because it would change globbing for the rest of the script.
   - If at least one root exists and `command -v unzip` fails, print exactly one
     `warn "Skipped IntelliJ plugin cleanup: unzip is not installed."` and skip the IntelliJ scan. Otherwise call
     `retired_ide_plugin_scan_root` for each root.
   - Then call `retired_ide_plugin_scan_vscode` for each of the four pairs, in this order:
     `"$HOME/.vscode/extensions" "VS Code" code`, `"$HOME/.vscode-insiders/extensions" "VS Code Insiders"
     code-insiders`, `"$HOME/.vscode-oss/extensions" VSCodium codium`, and `"$HOME/.cursor/extensions" Cursor cursor`.
   - Reporting:
     - If `retired_found -eq 0`, print `info "No retired Skill Bill IDE plugins found."`.
     - If `retired_removed -gt 0`, print `info "Restart ${label} if it is running to finish removing the Skill Bill
       plugin."` once for each label, then one
       `info "The Skill Bill desktop app replaces the IntelliJ plugin and VS Code extension; they are no longer supported."`.
     - If items were found but every removal failed, print only the `warn` lines: no "nothing found" line and no
       notice.
   - End with `return 0`. Never call `exit`.

Constraints: bash 3.2 safe (no `mapfile`, no `declare -A`, no `${var,,}`; declare arrays with `local -a` and expand
them with `${arr[@]+"${arr[@]}"}`). Guard every `rm` and every CLI call with
`if` or `|| true`. Quote every expanded path, because `$HOME` and `Application Support` contain or may contain
spaces. Keep `local` inside functions only. No running-IDE detection.

Tests: none of its own. It is covered by Task 5's smoke obligations 1-4.

### Task 2 - Call the cleanup on every install (criterion 3)

Path: `../../../install.sh`, `run_full_install` (about line 2477). Add `cleanup_retired_ide_plugins` on its own line directly
after the `migrate_legacy_config_to_durable_path` call (about line 2483).

Assumption for implement to confirm: search `../../../install.sh` for other call sites of
`migrate_legacy_config_to_durable_path` and for any other flow that completes an install without
`run_full_install`, such as a replay, update, or bundle-bootstrap re-exec path. Add the same call to each such flow.
If `bundle_bootstrap_if_needed` re-executes `install.sh` and then returns into `run_full_install`, a single call is
enough. Confirm the cleanup is not reached twice in one process.

Tests: smoke obligations 1-4 observe it through the install log.

### Task 3 - Reword the retired-release comments (criterion 5)

Path: `../../../install.sh`.

1. Above `resolve_latest_runtime_release_tag` (about lines 195-196), replace the comment with: "The API lists recent
   releases first. Retired `plugin-v*` / `extension-v*` releases remain on GitHub, so skip them and use the first
   versioned runtime tag."
2. Near line 460, change the latest-release comment so it says the redirect could be won by a retired `plugin-v*` /
   `extension-v*` release that remains on GitHub.

Do not touch any code line. Then search `../../../install.sh` for `plugin`, `extension`, `intellij`, and `vscode`, and
reword any other text that implies the plugins still ship. The new cleanup block's own strings are fine.

Tests: smoke obligation 6 keeps the existing scenario.

### Task 4 - Duplicate the block into `../../../uninstall.sh` and call it gated (criterion 4)

Path: `../../../uninstall.sh`.

1. Paste the marker-delimited block from Task 1 exactly, after the `info`/`ok`/`warn`/`err` helpers (about lines
   46-49) and before the top-level flow uses it. The script runs top to bottom, so the definition must come before
   the call.
2. In the top-level flow, after `remove_runtime_launchers` (about line 755) and the `remove_install_state` call, and
   immediately before the "Uninstall complete" summary, add:

   ```bash
   if [[ "${SKILL_BILL_PRESERVE_SOURCE_ON_WIPE:-}" != "1" ]]; then
     cleanup_retired_ide_plugins
   fi
   ```

3. Do not append plugin paths to `REMOVED_TARGETS` or `SKIPPED_TARGETS`.
4. Assumption for implement to confirm: search the repository's scripts and workflows for
   `SKILL_BILL_PRESERVE_SOURCE_ON_WIPE`. If anything other than `../../../install.sh`'s `run_pre_install_uninstall` (about lines
   741-763) sets it to `1`, add a dedicated variable instead. Name it `SKILL_BILL_SKIP_RETIRED_IDE_PLUGIN_CLEANUP=1`,
   pass it only from `run_pre_install_uninstall`, and gate on it.
5. If `../../../uninstall.sh` does not use `set -u`, the block still works unchanged. Do not alter the block to suit
   `uninstall.sh`.

Tests: smoke obligation 5 checks parity. Obligation 3 also checks the gate when the no-op scenario has prior install
state.

### Task 5 - Smoke-test obligations in `../../../scripts/install_smoke_test.sh` (Test Obligations 1-6)

Path: `../../../scripts/install_smoke_test.sh`. Reuse `FAKE_HOME`-style fake homes, PATH shim dirs, `pass`/`fail`, and
`assert_file_exists`/`assert_file_absent`. Fold the seeds into existing install scenarios: one seeded scenario covers
obligations 1, 2, and 4, and an existing clean scenario covers obligation 3. Each assertion reads the install log
that the scenario already captures. If a scenario does not capture its output, tee it to a file in its work dir.

1. Add a helper `make_plugin_jar <abs-jar-path> <plugin-id>`. It writes
   `META-INF/plugin.xml` (`<idea-plugin><id>ID</id></idea-plugin>`) into a fresh `mktemp -d` and zips it with
   `(cd "$tmp" && zip -q -r "$jar" META-INF)`. Assumption for implement to confirm by reading
   `../../../.github/workflows/install-smoke-test.yml`: the job runs on GitHub-hosted macOS/Ubuntu images that ship `zip` and
   `unzip`. If not, fall back to `python3 -I -c` with `zipfile`.
2. Seeded scenario, obligations 1 and 4. Pick the root from `uname -s`. On Darwin it is
   `"$fake_home/Library/Application Support/JetBrains/IntelliJIdea2024.3/plugins"`. On Linux it is
   `"$fake_home/.local/share/JetBrains/IntelliJIdea2024.3"`, and the scenario passes
   `XDG_DATA_HOME="$fake_home/.local/share"` explicitly so a runner-level value cannot redirect the scan.
   - Seed `renamed-root/lib/status.jar` with id `dev.skillbill.status`.
   - Seed `skill-bill-status/lib/status.jar` with id `com.example.other`.
   - Seed `unrelated/lib/u.jar` with id `org.example.unrelated`.
   - For obligation 4, unless `[[ "$(id -u)" == 0 ]]`, seed a second product root (`WebStorm2024.3`, same layout)
     holding a matching plugin dir, then `chmod a-w` that root before the install. Restore `chmod u+w` right after
     the install, and in the scenario's cleanup path, so the fake home can be deleted.
   - Assert `renamed-root` is absent, while `skill-bill-status` and `unrelated` exist.
   - Assert the log contains `removed IntelliJ plugin (IntelliJIdea2024.3):`.
   - Assert the log contains `Could not remove`, unless running as root.
   - Assert the install exited 0, which the existing scenario assertion already covers.
3. Same seeded scenario, obligation 2.
   - Seed `"$fake_home/.vscode/extensions/skill-bill.skill-bill-status-0.1.0"`,
     `"$fake_home/.cursor/extensions/skill-bill.skill-bill-status-0.1.0"`, and
     `"$fake_home/.vscode/extensions/ms-python.python-2024.1.0"`.
   - In the scenario's shim dir, add a `code` stub that appends `"$*"` to a record file and removes nothing.
   - Decision recorded here: also shadow `cursor` with a stub that only exits 1. The shim dir is prepended to the
     host `PATH`, so on a developer machine an unshadowed real `cursor` would run. A failing CLI and an absent CLI
     take the same deletion path, so the obligation's fallback branch is still what is exercised.
   - Assert the record contains `--uninstall-extension skill-bill.skill-bill-status`, both matching dirs are absent,
     `ms-python.python-2024.1.0` exists, and the log contains `removed VS Code extension (VS Code):`,
     `removed VS Code extension (Cursor):`, and exactly one `desktop app replaces` line.
4. Clean scenario, obligation 3. This is an existing install scenario with no seeds.
   - Assert `grep -c 'No retired Skill Bill IDE plugins found.'` on its log equals `1`. Use the
     `count="$(grep -c ... || true)"` form so a zero count does not trip `set -e`.
   - Assert the log has no `removed IntelliJ plugin`, `removed VS Code extension`, or `desktop app replaces` line.
   - If this scenario has prior install state, it also proves the `../../../uninstall.sh` gate does not print a second line.
5. Obligation 5, a static parity check that needs no install run. Extract the block from each script with
   `sed -n '/^# >>> retired IDE plugin cleanup/,/^# <<< retired IDE plugin cleanup/p'`. Assert both extracts are
   non-empty and identical, and `fail` with a short message when they differ.
6. Obligation 6. Leave the `plugin-v9.9.9` / `skill-bill-intellij-plugin-9.9.9.zip` fixture (about lines 436-445)
   and its assertions (about lines 881-895) unchanged. Reword only the comment at about lines 415-416 to say a
   retired `plugin-v*` release remains on GitHub.

Constraints: the test script must stay bash 3.2 safe. Compare paths with the same fake-home prefix the script
already builds, which avoids macOS `/var` vs `/private/var` mismatches. Add no other tests: reporting glue beyond
these obligations does not meet the test-value bar.

### Validation (validate phase only)

The validate phase runs `../../../scripts/install_smoke_test.sh` and the repository gate. No other phase runs them, and no
installer or uninstaller command is part of this plan.
