# .github/workflows — Boundary History

## [2026-10-15] SKILL-426 — avoid shared Gradle build-cache contention
Areas: .github/workflows/validate-agent-configs.yml
- The self-hosted `validate` job runs `./gradlew check --no-build-cache`. Other workflows can use the Mac mini's shared Gradle build cache at the same time; a cache lock timeout failed Spotless tasks in SKILL-426. The install smoke workflow already bypasses this shared cache for the same reason.

## [2026-09-22] SKILL-368 subtask 1 — spotless ratchet fetch retired
Areas: .github/workflows/validate-agent-configs.yml
- `ratchetFrom("origin/main")` is gone from `Quality.kt`, so the `Fetch origin/main for Spotless ratchet` step has no consumer and is removed. This supersedes `[2026-08-26] spotless-ratchet-origin-main-fetch` and `[2026-09-04] spotless-ratchet-fetch-must-not-shallow`; both entries stay for the record and neither applies to the current workflow.
- `actions/checkout` keeps `fetch-depth: 0` for a different reason: stable-tag version resolution needs the full tag list, and a shallow clone makes the runtime report `0.0.0-SNAPSHOT` and fails the update-check tests. Do not trade it for a shallow checkout now that the ratchet is gone. reusable
- Spotless now formats the whole tree on every run instead of the diff against `origin/main`, which also makes `spotlessCheck` pass inside linked `git worktree` clones where jgit could not resolve the gitdir file.
Feature flag: N/A
Acceptance criteria: N/A (CI follow-up to AC-004)

## [2026-08-30] SKILL-222 subtask 2 — extension-release workflow
Areas: .github/workflows/extension-release.yml, RELEASING.md, vscode-extension/README.md
- Extension ships on its own `extension-v*.*.*` tag stream via dedicated `extension-release.yml`; runtime `release.yml` and `plugin-release.yml` stay independent — an extension tag never builds the runtime/plugin and vice versa. reusable
- Job is hosted-only (`ubuntu-latest`); self-hosted runners stay off-limits for this packaging path. Tag remainder must be plain semver or the job fails closed.
- Asset staging is fail-closed: exactly one `skill-bill-vscode-extension-<version>.vsix` plus a verifiable `.sha256` sidecar; the asset check precedes release attach so a partial set never publishes. reusable
- Docs: RELEASING.md and extension README cover Install from VSIX; Marketplace publish and other workflow mutations stay deferred.
Feature flag: N/A
Acceptance criteria: 4/4 implemented (release packaging AC covered here; Stop/Pause live in vscode-extension history)

## [2026-09-04] spotless-ratchet-fetch-must-not-shallow
Areas: .github/workflows/validate-agent-configs.yml
- `git fetch --depth=1 origin +refs/heads/main:refs/remotes/origin/main` after a `fetch-depth: 0` checkout shallows the whole clone. `git describe` then fails, Gradle falls back to `0.0.0-SNAPSHOT`, and `UpdateCheckService` treats that sentinel as unversioned — `CliRuntimeUpdateTest` fails on both matrix legs.
- Fetch `origin/main` without `--depth=1`. Checkout already has full history; Spotless only needs the `origin/main` ref.
Feature flag: N/A
Acceptance criteria: N/A (CI follow-up)

## [2026-08-26] spotless-ratchet-origin-main-fetch
Areas: .github/workflows/validate-agent-configs.yml
- Hosted ubuntu `validate` failed at `:runtime-application:spotlessCheck` with `No such reference 'origin/main'` because Spotless `ratchetFrom("origin/main")` (Quality.kt) needs that ref at task-graph creation, and `actions/checkout@v5` default `fetch-depth: 1` never creates it. Self-hosted macmini already had a fuller clone, so only the ubuntu matrix leg failed.
- After checkout, fetch `origin/main` into `refs/remotes/origin/main` before `./gradlew check`. Idempotent on the self-hosted runner. Do not skip ratchet when the ref is missing: that would format the whole tree and fail on unrelated files. Do not use `--depth=1` (see 2026-09-04).
Feature flag: N/A
Acceptance criteria: N/A (CI follow-up)

## [2026-08-07] SKILL-167 subtask 1 plugin-ci-workflow
Areas: .github/workflows/plugin-ci.yml
- New `plugin-ci.yml` gates `intellij-plugin/**` on PR + push-to-main via a `check` job running the exact command `(cd intellij-plugin && ./gradlew check)`; the plugin build is a standalone Gradle build, so CI must `cd` into it rather than invoke the root wrapper.
- Split-job event gating pattern (reusable): one workflow, two jobs keyed on `if: github.event_name != 'schedule'` / `== 'schedule'`, so the expensive `verifyPlugin` leg (downloads two full IDEs) runs only on the nightly `cron: 17 3 * * *` and never on push/PR. Schedule events ignore `paths` filters by design — relying on that is intentional, not an oversight.
- Both jobs pin `runs-on: ubuntu-latest` and `actions/setup-java@v5` with temurin 21 + `cache: gradle`. Unlike `validate-agent-configs.yml`, nothing in this workflow may reach the self-hosted runner for any event.
- `verifyPlugin` deliberately carries no `failureLevel` override — the plugin build script owns that setting; overriding it in CI would silently diverge from local runs.
- `concurrency` group `${{ github.workflow }}-${{ github.ref }}` with `cancel-in-progress: true`, matching the repo's other PR workflows.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (subtask 1 of 3; release workflow + docs in subtask 3)

## [2026-06-13] SKILL-82 subtask 1 skills-bundle-step
Areas: .github/workflows/release.yml
- Bundle step guarded by `if: matrix.host_token == 'linux-x64'` on the build job; tars skills/, platform-packs/, orchestration/, uninstall.sh into skill-bill-skills-${RELEASE_VERSION}.tar.gz + .sha256 (sha256sum available on ubuntu-latest). reusable
- Artifact name `release-assets-skills` is auto-covered by publish job's `pattern: release-assets-*` download — any new build-leg artifact named `release-assets-*` is picked up without changing the publish job. reusable
- `RELEASE_VERSION` is set via `$GITHUB_ENV` in 'Set release version' step; available as env var in subsequent steps on the same runner leg. reusable
Feature flag: N/A
Acceptance criteria: 2/4 parent ACs (CI pipeline only; bootstrap in subtask 2, smoke test in subtask 3)

## [2026-05-29] ci-release-pipeline (SKILL-55 subtask 3)
Areas: .github/workflows, RELEASING.md, .github/actionlint.yaml
- `release.yml` now runs a `build` matrix (fail-fast:false) that produces per-OS artifacts on host-matched runners, then a `publish` job (`needs: build`) attaches them. jlink/jpackage cannot cross-compile, so one OS per matching runner.
- Runner→token map (canonical, matches subtasks 1-2 filenames): macos-14→macos-arm64, macos-15-intel→macos-x64, windows-latest→windows-x64, ubuntu-latest→linux-x64. Linux leg must `apt-get install fakeroot rpm` for .deb/.rpm.
- Build installers via host-native `:runtime-desktop:canonicalRename<Format>Installer` tasks, NOT the `packageDesktopInstallers` aggregate (the aggregate pulls all four formats and fails jpackage on the wrong host). Build images via `:runtime-cli:runtimeZip :runtime-mcp:runtimeZip`.
- Validation gate (gradlew check, agnix Found-0-errors, validate_agent_configs, validate_release_ref) is preserved verbatim and runs fail-closed in `publish` before any download/attach. Do not weaken it to make the matrix pass.
- Staging (reusable for downstream asset testing): push a `-rc.N` prerelease tag OR run `workflow_dispatch` with `staging_version`; staging path skips validate_release_ref, always publishes a prerelease, and omits `--verify-tag` (no pushed tag). Publish is idempotent: `gh release upload --clobber` if the release exists else `gh release create`.
- `.github/actionlint.yaml` (reusable) registers `macos-15-intel` so actionlint's stale runner-label DB stops false-failing; add new hosted labels here, not as suppressions.
- Known limit: macOS x64 depends on the `macos-15-intel` hosted image; if retired, drop that leg (fail-fast:false keeps the rest publishing). Installers are unsigned for v1.
Feature flag: N/A
Acceptance criteria: 7/7 implemented
