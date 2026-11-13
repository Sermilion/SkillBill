# runtime-kotlin

Standalone Kotlin runtime for the local `skill-bill` CLI, MCP server, install
planner/apply path, telemetry, workflow state, scaffolding, and governed
validation.

## Build

```bash
./gradlew check
```

## Self-contained runtime images (jlink)

`runtime-cli` and `runtime-mcp` can be packaged as self-contained images that
bundle a trimmed Java runtime, so they run on a machine with **no system JDK**.
These are produced with the Badass Runtime plugin (`org.beryx.runtime`, jlink
under the hood) and are independent of the existing `installDist` outputs.

Build the host images and their versioned archives:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:runtimeZip :runtime-mcp:runtimeZip)
```

The image name and archive name derive from `project.version` (no hardcoded
version). The canonical per-OS/arch target tokens are defined once in the
`skillbill.runtime-image` convention plugin
(`build-logic/convention/.../RuntimeTargets.kt`), consumed as a typed contract:

- `macos-arm64`
- `macos-x64`
- `windows-x64`
- `linux-x64`

**Cross-compilation is not supported** — each target's image must be built on a
matching OS/arch. Locally only the current host's image is reproducible (on this
repo's reference host, `linux-x64`); `macos-*` and `windows-x64` are built on
matching CI runners (subtask 3).

### Outputs and unpacked layout

The archive lands under each module's build directory (gitignored):

```
runtime-cli/build/runtime-image/runtime-cli-<version>-<os>-<arch>.zip
runtime-cli/build/runtime-image/runtime-cli-<version>-<os>-<arch>.zip.sha256
runtime-mcp/build/runtime-image/runtime-mcp-<version>-<os>-<arch>.zip
runtime-mcp/build/runtime-image/runtime-mcp-<version>-<os>-<arch>.zip.sha256
```

Each archive carries an adjacent `.sha256` checksum file. Unzipping yields a
stable `image/` layout with a `bin/` launcher and the bundled `bin/java`:

```
image/
  bin/
    runtime-cli        # CLI launcher (subtask 4 symlinks this to `skill-bill`)
    java               # bundled trimmed JRE — no system JDK required
  lib/
  conf/
  legal/
```

```
image/
  bin/
    runtime-mcp        # MCP launcher (subtask 4 symlinks this to `skill-bill-mcp`)
    java
  lib/                 # includes the generated telemetry-event schema resource
  ...
```

Run the unpacked launcher directly — it uses the bundled `bin/java`:

```bash
unzip runtime-cli-<version>-<os>-<arch>.zip -d /tmp/skill-bill-image
/tmp/skill-bill-image/image/bin/runtime-cli version
```

### Verification matrix

The no-JDK run (`sona version` via `bin/runtime-cli`) and the `runtime-mcp`
stdio smoke are exercised locally on **`linux-x64` only**. `macos-arm64`,
`macos-x64`, and `windows-x64` are built and verified on their matching CI
runners (subtask 3); they cannot be reproduced from a Linux host.

The local smoke test only exercises `version` / stdio-init; full per-module path
verification (e.g. every jlink module the telemetry HTTP path needs) happens in CI
(subtask 3).

> The existing `:runtime-cli:installDist` / `:runtime-mcp:installDist` outputs are
> **unchanged** — the runtime images are an additive, separate distribution path.

## IntelliJ / Android Studio

`runtime-kotlin` is a nested standalone Gradle project inside the larger `skill-bill` repo.

If you open only the outer `skill-bill` repository as a plain IntelliJ project,
Kotlin source files under module paths such as
`runtime-kotlin/runtime-core/src/main/kotlin` and
`runtime-kotlin/runtime-mcp/src/main/kotlin` may show unresolved `skillbill.*`
imports even though Gradle builds successfully.

Use one of these setups instead:

1. Open `runtime-kotlin/` as its own IntelliJ project.
2. If you keep the outer repo open, attach `runtime-kotlin/build.gradle.kts` as a Gradle project and run a Gradle sync.

If imports stay red after attaching the build, reload the Gradle project or restart the IDE.
