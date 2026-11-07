# Desktop QA

Launch the desktop app for QA with:

```bash
cd desktop && ./gradlew :app:runFromSource
```

`stageSourceRun` runs first. It builds `runtime-cli` from this checkout, copies the install image and the app resources into a scratch directory, and stamps one fresh install run id into both `build-identity.properties` records. The runtime gate then sees a matching pair and reports the runtime as available. Plain `:app:run` reads the installed runtime and reports the runtime as unavailable unless that install matches the checkout.

The first launch is slow because it builds `runtime-kotlin`.

## Desktop preview

Open the execution's Details pane to watch its private desktop display. For a Goal,
select the subtask running QA, or select Goal finish for final QA. The Desktop preview
section shows Waiting until the selected invocation produces a framebuffer, Live while
frames arrive, and Ended after QA closes. Closing the pane stops observation.
Standalone QA uses the selected run in its history pager. Only desktop targets with
a private file-backed Xvfb display supply frames. Earlier runs without preview metadata
have no preview.

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
- `SKILL_BILL_DB=desktop/app/build/source-run/home/.skill-bill/review-metrics.db`
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
