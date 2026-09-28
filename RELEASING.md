# Releasing Skill Bill

Skill Bill uses tag-driven GitHub Releases.

The release contract is:

- create an annotated SemVer tag such as `v0.4.0`
- push the tag to GitHub
- let the `Release` workflow rerun validation and publish the GitHub Release

Releases use the [MIT License](LICENSE). The [licensing summary](docs/licensing.md)
describes the permissions and treatment of earlier releases. The release
validator requires the complete MIT license text for every release tag.

Pre-release tags such as `v0.5.0-rc.1` are also supported and publish GitHub
prereleases. Stable and prerelease versions use the same license checks.

## What a tag push builds

Pushing a SemVer tag now does more than publish notes. The `Release` workflow
builds, on matching host runners, the full per-OS asset set and attaches every
archive plus its `.sha256` sidecar to the GitHub Release for that tag:

- self-contained runtime images for **CLI** and **MCP**
  (`runtime-cli-<version>-<os>-<arch>.zip`, `runtime-mcp-<version>-<os>-<arch>.zip`)

The `<os>-<arch>` token is the canonical set `macos-arm64` / `windows-x64` /
`linux-x64`, so downstream installers can resolve the correct asset by host. Each
artifact ships with a matching `<name>.sha256` file. The build toolchain is
pinned to JDK 21 (temurin) on every runner. Before upload, each host extracts or
mounts its native artifacts and compares the included root `LICENSE` bytes; it
also verifies the adjacent checksum after the licensed artifact is finalized.
The Linux skills archive includes the root `LICENSE` directly and is checked the
same way.

Builds run on host-matched runners because jlink and jpackage cannot
cross-compile: macOS arm64 on the **self-hosted Apple Silicon Mac mini**
(label `macmini`), Windows x64 on `windows-latest`, Linux x64 on `ubuntu-latest`.
Apple Silicon is the only supported macOS target — the Intel (`macos-x64`) leg
was dropped. Installers ship **unsigned for v1** (see
`runtime-kotlin/agent/decisions.md`).

> **Self-hosted macOS leg.** The `macos-arm64` build runs on the self-hosted Mac
> mini, so it must be online when a release tag is pushed. If it is offline that
> leg queues until it comes back; the workflow's `fail-fast: false` strategy lets
> the hosted Windows/Linux legs publish regardless, with the macOS asset filled
> in once the mini runs.

## Staging a release for downstream testing

Downstream work (such as the prebuilt-first installer) sometimes needs a real,
downloadable asset set before a stable tag is cut. There are two ways to produce
one, both of which publish a GitHub **prerelease** so it never looks like a
stable release:

1. **Prerelease tag** — push a `v*.*.*-rc.N` tag (e.g. `v0.5.0-rc.1`). The
   workflow validates the ref, builds every per-OS asset, and publishes a GitHub
   prerelease with all archives + `.sha256` files attached.
2. **Manual run (`workflow_dispatch`)** — trigger the `Release` workflow from the
   Actions tab and supply a SemVer prerelease `staging_version` label (e.g.
   `v0.5.0-staging.1`). No tag push is required; the workflow rejects a stable
   version label, builds the same per-OS asset set, and publishes a prerelease
   named from the input, so testers get a real downloadable asset set.

Both paths reuse the same build matrix and the same fail-closed release-policy
validator as a stable release. Every staging label is immutable evidence for
one asset set: the workflow refuses an existing release or staging tag instead
of replacing assets, so use a fresh prerelease label for every new matrix run.
The workflow creates a draft, uploads and confirms the complete expected asset
set, then publishes it; a failed upload therefore cannot make a partial stable
release public.

## Release notes

### SKILL-32 Python Runtime Retirement

Skill Bill now uses packaged Kotlin as the only normal CLI and MCP runtime.
Rollback is to install the previous Skill Bill release, not to select a Python
runtime.

Removed environment variables:

- `SKILL_BILL_RUNTIME`
- `SKILL_BILL_MCP_RUNTIME`

Removed files:

- the entire historical runtime package and its packaging manifest
- `scripts/mcp_server_start.sh`
- legacy Python runtime modules for review, learnings, workflow telemetry,
  quality-check, PR description, and SQLite runtime support

## Versioning policy

Skill Bill should stay on pre-1.0 SemVer until the install surface, taxonomy, and stable entry points feel settled.

- bump `patch` for docs-only work, validator fixes, and non-breaking tooling or installer fixes
- bump `minor` for new skills, new platform coverage, new routing behavior, or other user-visible capability additions
- reserve `major` for intentional breaking changes to taxonomy, install behavior, or stable entry points

## Cutting a release with `/skill-bill operation:release`

The `/skill-bill operation:release` operation automates the changelog-and-tag path
of the checklist below. Pass the bump type and it will:

1. verify the working tree is clean and the branch is up to date with its remote
2. find the previous stable tag and gather every commit since it
3. generate a curated, user-facing changelog (New Features / Bug Fixes / Other),
   reading this file first for the versioning policy, and present it for review
4. compute the next version from the bump type and confirm it with you
5. create the annotated `vX.Y.Z` tag and push it after an explicit confirmation,
   triggering the `Release` workflow

```text
/skill-bill operation:release bump:minor
```

The bump type is required — `bump:patch`, `bump:minor`, or `bump:major`, per the
versioning policy above. The operation never creates a tag with a dirty tree, never
skips changelog review, and never pushes without confirmation. It does not run
the local validation gate, so run the checks in the checklist below first (or let
the `Release` workflow re-run them on the pushed tag).

## Release checklist

Use this when cutting a release by hand, or to know what `/skill-bill operation:release` does
under the hood.

1. Make sure the release commit is on `main`.
2. Run the local checks:

   ```bash
   skill-bill validate
   (cd runtime-kotlin && ./gradlew check)
   npx --yes agnix --strict .
   scripts/validate_agent_configs
   ```

3. Confirm the hosted telemetry relay runs this checkout's contract. A mismatch means the
   Worker was not redeployed after a relay change:

   ```bash
   (cd docs/cloudflare-telemetry-proxy && npm run --silent check-deployed)
   ```

4. Confirm that the root [LICENSE](LICENSE) contains the MIT license and that
   release artifacts include it.
5. Pick the next canonical `v`-prefixed version tag and run
   `scripts/validate_release_ref v0.x.y`. A staging build must use a SemVer
   prerelease label such as `v0.x.y-staging.1`.
6. Create an annotated tag:

   ```bash
   git tag -a v0.x.y -m "Release v0.x.y"
   ```

7. Push the tag:

   ```bash
   git push origin v0.x.y
   ```

8. Confirm the `Release` workflow succeeds and the GitHub Release appears with
   generated notes and the per-OS runtime-image assets (each with its `.sha256`)
   attached. Confirm the published artifacts passed the
   root-license byte check on their native release-matrix host.

## Installing from a release

If you want a stable install target instead of following `main`, clone the repo at a tag and run the normal installer:

```bash
TAG=v0.x.y
git clone --branch "$TAG" --depth 1 <this-repo> ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

## Releasing the IntelliJ plugin

The IntelliJ plugin releases on its own tag stream, separate from the runtime.

- **Tag format**: `plugin-vX.Y.Z` (plain semver only — `plugin-v0.1.0`, never
  `plugin-v0.1` or `plugin-v0.1.0-rc.1`). Pushing such a tag runs
  `.github/workflows/plugin-release.yml`, which derives the build version from the
  tag (`plugin-v0.1.0` → `0.1.0`) and passes it to Gradle as `-Pversion`;
  `intellij-plugin/gradle.properties` keeps its `-SNAPSHOT` version for local work.
- **Independent versioning**: the plugin version has no relationship to the runtime
  semver in `VERSION`. Plugin compatibility is dictated by the IDE build range in
  `intellij-plugin/build.gradle.kts`, not by which runtime release is current.
- **Published assets**: exactly two —
  `skill-bill-intellij-plugin-<version>.zip` and its `.sha256` sidecar. The workflow
  fails before creating the release if either is missing or if any other file is
  staged.
- **Never cross the streams**: a `plugin-v*` tag must never be used to cut a runtime
  release, and a runtime `v*` tag must never build or publish the plugin. Runtime tags
  are handled solely by `release.yml`, which knows nothing about the plugin.

## Releasing the VS Code extension

The VS Code extension releases on its own tag stream, separate from the runtime and
IntelliJ plugin.

- **Tag format**: `extension-vX.Y.Z` (plain semver only — `extension-v0.1.0`, never
  `extension-v0.1` or `extension-v0.1.0-rc.1`). Pushing such a tag runs
  `.github/workflows/extension-release.yml`, which derives the build version from the
  tag (`extension-v0.1.0` → `0.1.0`) and passes it to `npm version` before `vsce package`.
- **Published assets**: exactly two —
  `skill-bill-vscode-extension-<version>.vsix` and its `.sha256` sidecar. The workflow
  fails before creating the release if either is missing or if any other file is staged.
- **Never cross the streams**: an `extension-v*` tag must never be used to cut a runtime
  or plugin release, and runtime `v*` / `plugin-v*` tags must never build the extension.
  Self-hosted runners are not used for extension packaging.
