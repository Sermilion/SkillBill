You are curating the changelog for release {{version}} of this repository. This step is read-only: do not
edit, stage, commit, tag, or push anything.

Previous release tag: {{previous_tag}}

Commits since the previous release (one per line, short sha and subject):

{{commit_log}}

Your final value is the changelog markdown alone, starting with `## What's New in {{version}}`. The runtime stores
it and uses it verbatim as the annotated tag message once the operator confirms.

# Release Skill Content

## Overview

This skill produces a curated user-facing changelog from commits since the last release, presents it for review, then creates and pushes an annotated semver tag. Pushing the tag triggers whatever CI/CD or release workflow the repo has wired to tag events.

## Intake

Require a bump-type argument: `bump:patch`, `bump:minor`, or `bump:major`. If it is absent or not one of those three values, stop and ask the user which bump type to use before proceeding. Do not guess or suggest a default.

## Steps

### 2. Find the previous release tag

```bash
git tag --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1
```

If no stable tag exists yet, use the root commit as the base (first commit SHA).

### 3. Gather commits since the previous tag

```bash
git log <prev-tag>..HEAD --oneline
```

Also pull richer context for merge commits:

```bash
git log <prev-tag>..HEAD --merges --format="PR: %s%n%b"
```

### 4. Generate the changelog draft

Categorize the commits using editorial judgment. If the repo has a RELEASING.md or CHANGELOG.md, read it first for project-specific versioning policy. Use commit messages to determine what is user-facing, important, or internal.

**Categories:**

- **New Features** — new user-visible capabilities (new skills, new commands, new runtime modes, new UX flows). Each gets its own bullet.
- **Bug Fixes** — notable, user-impacting fixes worth naming individually. Threshold: would a user notice or care? If yes, name it. Examples: broken command behavior, incorrect output, crashes, data loss, wrong state handling.
- **Other bug fixes and stability improvements** — one grouped bullet covering everything else (internal refactors, minor telemetry tweaks, test-only changes, doc cleanups, minor infra changes, dependency bumps). Do **not** itemize these.

Format:

```markdown
## What's New in vX.Y.Z

### New Features
- <Feature name>: <one-sentence description of the user-visible change>

### Bug Fixes
- <Fix name>: <one-sentence description of what was broken and is now fixed>

### Other
- Other bug fixes and stability improvements.
```

Omit any section that has no entries. If there are only minor changes, the entire changelog may collapse to the "Other" bullet alone.

Do not include:
- Internal implementation details
- Test-only changes
- Commit SHAs or PR numbers (unless the user prefers them)
- Passive voice or marketing language

### 5. Determine the next version

Apply the required bump type from intake to compute the next version (e.g. `v0.4.0`).

## Rules

- Confirm exactly once before creating and pushing the tag — do not re-prompt for the tag creation and the push as separate gates.
- Never create or push the tag without that explicit user confirmation.
- Never create the tag if the working tree has uncommitted changes.
- Never skip the user review of the changelog draft (it is included in the single confirmation).
- If `git fetch` fails due to network issues, warn the user but let them decide whether to continue.
- Prerelease tags (`v0.5.0-rc.1`) are valid — the workflow publishes them as GitHub prereleases.
