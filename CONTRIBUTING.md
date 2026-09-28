# Contributing to Skill Bill

Skill Bill is pre-1.0 and solo-maintained. Contributions are welcome. A few
things to know before contributing:

## Licensing

By submitting a contribution, you agree to license it under the project's
[MIT License](LICENSE). You represent that you have the authority to submit it
under those terms. No CLA or sign-off is required.

## Quality Gate

Run the full gate before proposing changes that touch the runtime, scaffold,
contracts, or agent-config files:

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

`./gradlew check` builds and runs the Kotlin test suite. `validate_agent_configs`
checks generated agent-config manifests for drift. See the
[Validation Gate section in Getting Started](docs/getting-started.md#validation-gate)
for the full description.

### IntelliJ plugin

The IntelliJ status plugin lives in top-level `intellij-plugin/` with its own
Gradle project (IntelliJ Platform Gradle Plugin 2.x, JDK 21). It is not part of
`runtime-kotlin`. Before proposing plugin changes:

```bash
(cd intellij-plugin && ./gradlew check)
```

Use `./gradlew buildPlugin`, `runIde`, and `verifyPlugin` as described in
[`intellij-plugin/README.md`](intellij-plugin/README.md). Architecture,
persistence policy, source-of-truth, and the future tool-window extension path
are documented in [`intellij-plugin/ARCHITECTURE.md`](intellij-plugin/ARCHITECTURE.md).

## Extension Point: Platform Packs

The documented extension surface is **platform packs** under
`platform-packs/<lang>/`. A pack is a directory with:

- `platform.yaml` — routing signals and pack metadata
- `code-review/` — stack-specific code-review skill definitions per area
- `validation_gate` — pack quality-check commands and findings contract

Adding a new language pack is additive: drop in `platform-packs/<lang>/` and
`/skill-bill phase:review` and `/skill-bill phase:validation` start routing to
it automatically — no changes to generic skill files required. See
[Skill Source and Generation Model](docs/skill-source-generation.md) for the
source layout and render contract.

## Extending via the MCP Tool Surface

`skill-bill-mcp` exposes local runtime primitives as structured MCP tools.
These are documented in the
[MCP Server section of Getting Started](docs/getting-started.md#mcp-server).
The tool surface is stable for review, workflow-state, and telemetry
operations; new tools ship as additive extensions.

## Pre-1.0 Caveat

This project is pre-1.0. Top-level skill names, skill taxonomy, and
platform-pack conventions may still change between releases. Before investing
significant effort in a patch that touches these areas, open an issue first
to check whether the affected area is already in flux.

## Issues and PRs

- File bugs, questions, or feature requests at the GitHub issue tracker.
- Open PRs against `main`. A passing quality gate and a clear description of
  what changed and why are the only required conventions.
- No CLA or sign-off ritual required.
