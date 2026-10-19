# Skill Bill VS Code extension

Isolated VS Code extension that surfaces Skill Bill feature-work status in the
status bar. This directory is a **sibling** of `runtime-kotlin/` and
`intellij-plugin/` with its own npm build — it is not included in Gradle and
adds no VS Code dependency to the Kotlin runtime.

See [ARCHITECTURE.md](ARCHITECTURE.md) for package ownership, persistence policy,
and source-of-truth rules.

## Requirements

- Node.js 22+
- A `skill-bill` CLI on `PATH`, in `${SKILL_BILL_BIN_DIR:-~/.local/bin}`, or
  named by the `skillBill.cliPath` workspace setting

## Installing the extension

Primary path — install the released VSIX:

1. Open the GitHub Release for the `extension-vX.Y.Z` tag you want.
2. Download `skill-bill-vscode-extension-<version>.vsix` (its `.sha256` sidecar is
   published alongside it if you want to verify the download).
3. In VS Code: **Extensions → … → Install from VSIX…**, pick the VSIX.
4. Reload the window when prompted.

Fallback — build from source:

```bash
cd vscode-extension
npm install
npm run package
```

Install the generated `.vsix` with the same **Install from VSIX…** step.

Marketplace publish stays deferred.

## CLI path resolution

1. `skillBill.cliPath` workspace setting, when set
2. Else `skill-bill` on `PATH`
3. Else `$SKILL_BILL_BIN_DIR`, then `~/.local/bin`

An override that is set but not executable is **misconfigured** and never falls
back to PATH.

## Goal controls

The status details popup offers **Stop goal** and **Pause after current subtask**
when the snapshot reports an active `feature-goal` with an issue key:

- **Stop goal** → `skill-bill goal stop <issue-key> --repo-root <canonical>`
- **Pause after current subtask** → `skill-bill goal pause <issue-key> --repo-root
  <canonical>`

Each mutating verb runs on its own `ProcessRunner` instance so a mutation cannot
return a status poll's exit code. Failures surface as a bounded summary that never
carries process output, stderr, or filesystem paths. The control renders disabled
while the snapshot reports a pause already requested.

There is deliberately **no Resume control**. Goal launch, resume, retry, and
abandon stay CLI-only and deferred.

## Common tasks

```bash
cd vscode-extension
npm run compile
npm run package
```

Do **not** fold these tasks into `./gradlew check`. The extension builds
independently of `runtime-kotlin` and `intellij-plugin`.

## Out of scope

- Marketplace listing, publisher identity, or signing beyond local/CI package
- Full tool window UI
- Goal launch, resume, retry, or abandon controls
