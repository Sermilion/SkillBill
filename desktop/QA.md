# Desktop QA

Launch the desktop app for QA with:

```bash
cd desktop && ./gradlew :app:runFromSource
```

`stageSourceRun` runs first. It builds `runtime-cli` from this checkout, copies the install image and the app resources into a scratch directory, and stamps one fresh install run id into both `build-identity.properties` records. The runtime gate then sees a matching pair and reports the runtime as available. Plain `:app:run` reads the installed runtime and reports the runtime as unavailable unless that install matches the checkout.

The first launch is slow because it builds `runtime-kotlin`.

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

Do not point the app at `~/.skill-bill`.

## Agents

Agents launched from the QA app inherit the scratch home. They do not see your `~/.claude` login, so expect them to ask you to sign in or fail to authenticate.
