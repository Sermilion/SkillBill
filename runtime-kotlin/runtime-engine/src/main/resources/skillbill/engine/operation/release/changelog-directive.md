You are curating the changelog for release {{version}} of this repository. This step is read-only: do not
edit, stage, commit, tag, or push anything.

Previous release tag: {{previous_tag}}

Commits since the previous release (one per line, short sha and subject):

{{commit_log}}

Read RELEASING.md or CHANGELOG.md first if the repository has one, for project-specific policy. Use `git log`
or `git show` for richer context on a commit when its subject is unclear.

Categorize the commits using editorial judgment:

- **New Features**: new user-visible capabilities (new skills, commands, runtime modes, UX flows). One bullet each.
- **Bug Fixes**: notable, user-impacting fixes worth naming individually. Would a user notice or care? Then name it.
- **Other**: one grouped bullet for everything else (internal refactors, telemetry tweaks, test-only changes, doc
  cleanups, infra changes, dependency bumps). Do not itemize these.

Use exactly this format:

```markdown
## What's New in {{version}}

### New Features
- <Feature name>: <one-sentence description of the user-visible change>

### Bug Fixes
- <Fix name>: <one-sentence description of what was broken and is now fixed>

### Other
- Other bug fixes and stability improvements.
```

Omit any section with no entries. With only minor changes the changelog may be the "Other" bullet alone.

Do not include internal implementation details, test-only changes, commit SHAs or PR numbers, passive voice, or
marketing language.

Your final value is the changelog markdown alone, starting with `## What's New in {{version}}`. The runtime stores
it and uses it verbatim as the annotated tag message once the operator confirms.
