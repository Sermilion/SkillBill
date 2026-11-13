# Desktop QA

Launch the desktop app for QA with:

```bash
cd desktop && ./gradlew :app:runFromSource
```

`stageSourceRun` runs first. It builds `runtime-cli` from this checkout, copies the install image and the app resources into a scratch directory, and stamps one fresh install run id into both `build-identity.properties` records. The runtime gate then sees a matching pair and reports the runtime as available. Plain `:app:run` reads the installed runtime and reports the runtime as unavailable unless that install matches the checkout.

The first launch is slow because it builds `runtime-kotlin`.

## Desktop preview

Open the execution's Details pane to watch its private desktop display. For a Goal,
select the subtask running QA, or select Goal finish and open its QA tab for final QA. The
Desktop preview section shows Waiting until the selected invocation produces a framebuffer
with something drawn on it, Live while frames arrive, and Ended after QA closes. An empty
display paints one solid color, so a uniform frame stays Waiting. Closing the pane stops observation.
Standalone QA uses the selected run in its history pager. Only desktop targets with
a private file-backed Xvfb display supply frames. Earlier runs without preview metadata
have no preview.

Xvfb owns `live-preview/Xvfb_screen0`. Screenshot tools must write separate files
under the invocation's `evidence/` directory. Writing a screenshot into the live
framebuffer can truncate its memory-mapped storage and crash Xvfb with `SIGBUS`.
Protect the ready framebuffer with `chmod 400` before launching clients. Xvfb's
existing memory mapping can still update it, while screenshot tools cannot open
it for writing. After a display crash, preserve the log and confirm that the old
process has exited before restoring write permission with `chmod 600` and
restarting with the same `-fbdir` directory. Protect the replacement framebuffer,
register the replacement process and relaunch the app on its new display number.
A retry without `-fbdir` cannot supply live preview and must report that limitation.

## Seeded data

Every `runFromSource` launch regenerates the fixture through the runtime's own migrations with
`:runtime-core:writeDesktopFixtureDb`, then copies it into the scratch home. It contains goals,
task-runtime workflows with phases, verify runs, standalone phases and spec bundles using `QAFIX`
issue keys. The generated database stays under `build/` and is never committed. Active standalone
rows and live goal leases belong to the generator process, so they can appear stale, interrupted or
paused after that process exits. Running and pause-requested goal bundles then read as paused.

## Scratch layout

All state lives under `desktop/app/build/source-run/`:

- `runtime/` holds the staged `runtime-cli` install image.
- `app-resources/` holds the staged app resources and the app's build identity record.
- `home/` is the scratch home. The task deletes and recreates it on every launch, so nothing carries over between runs.

## Overrides

The app gets these JVM properties:

- `-Dcompose.application.resources.dir=desktop/app/build/source-run/app-resources`
- `-Duser.home=desktop/app/build/source-run/home`
- `-Djava.util.prefs.userRoot=desktop/app/build/source-run/home/preferences`
- `-Djava.io.tmpdir=desktop/app/build/source-run/home/tmp`

And these environment variables, which replace any value exported in your shell:

- `SKILL_BILL_RUNTIME_DIR=desktop/app/build/source-run/runtime`
- `SKILL_BILL_STATE_DIR=desktop/app/build/source-run/home/.skill-bill`
- `SKILL_BILL_REVIEW_DB=desktop/app/build/source-run/home/.skill-bill/review-metrics.db`
- `HOME` and `USERPROFILE` point at the scratch `home/` for child processes.
- `APPDATA` and `LOCALAPPDATA` point at `home/AppData/Roaming/` and `home/AppData/Local/`.
- `XDG_CONFIG_HOME`, `XDG_CACHE_HOME`, `XDG_DATA_HOME` and `XDG_STATE_HOME` point at `home/.config/`, `home/.cache/`, `home/.local/share/` and `home/.local/state/`.
- `CLAUDE_CONFIG_DIR`, `CODEX_HOME` and `CURSOR_CONFIG_DIR` point at `home/.claude`, `home/.codex` and `home/.cursor`.
- `TERM=dumb`, set only when `TERM` is unset or blank, so the app does not import your login shell's environment over the `SKILL_BILL_*` values

Do not point the app at the operator's state database. Goal checkouts may live beneath
`~/.skill-bill`; QA writes only the verified checkout's fixed fixture and source-run build outputs.
The tasks refuse redirected paths or linked entries before deleting or copying data. Keep these
QA output directories exclusive to the task while it runs. A missing fixture preserves the previous
scratch home and stops the launch.

## Agents

Agents launched from the QA app inherit the scratch home. They do not see your `~/.claude` login, so expect them to ask you to sign in or fail to authenticate.

## Phase card screenshot checks

`PhaseCardSnapshotTest` clicks each subtask and goal-finish pill, then opens every disclosure.
It checks 88 images across 360 px and 760 px panel widths, including repair phases and
`plan_subtasks`. The fixtures include long criteria, findings, plan provenance, validation
overflow, fixed QA evidence, and both history files. Time, time zone, locale, density, fonts and motion are fixed.

Run the checks on Linux with the bundled fonts:

```sh
cd desktop
xvfb-run --auto-servernum ./gradlew :feature:detail:jvmTest --tests '*PhaseCardSnapshotTest'
```

The tests compare against `feature/detail/src/jvmTest/resources/phase-cards/`.
They allow channel differences of 2 and at most 0.2% changed pixels. Size changes fail.
Actual images and pink pixel diffs go to `feature/detail/build/reports/phase-cards/`.
Other operating systems skip these pixel comparisons and keep running the interaction tests.
Linux CI uploads the images when checks fail.

After reviewing a deliberate design change, replace the baselines explicitly:

```sh
SONA_UPDATE_PHASE_SNAPSHOTS=1 xvfb-run --auto-servernum ./gradlew :feature:detail:jvmTest --tests '*PhaseCardSnapshotTest'
```

The HTML reference has a separate capture command:

```sh
cd design/linear-study
npm ci
npx playwright install chromium
npm run screenshots
```

Open `build/phase-cards/index.html` to inspect each clicked pill, collapsed and expanded,
at both widths. The script serves the prototype locally and uses the desktop's bundled fonts.
It captures all ten subtask pills and seven goal-finish pills from the completed reference run.
The manifest records the disclosures opened and the resulting text.

Compare hierarchy, spacing, wrapping and available actions between the HTML references and
Compose images. Each renderer draws text differently, so HTML pixels are not Compose baselines.
Prototype sample counts, durations and evidence are not runtime facts. The app shows retained
records and names missing historical evidence instead of copying sample data.
