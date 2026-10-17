# SKILL-428 subtask 2: Reuse a working validation environment

## Scope

Improve existing repair instructions and repository setup guidance so the agent establishes a working validation environment early and retains useful setup across repair rounds. This is prose guidance using current commands and CI declarations.

## Acceptance Criteria

1. S2-1. The repair briefing directs the agent to read applicable repository instructions and CI/setup scripts before choosing host or container validation. It checks likely toolchain, tool, writable-home/cache/temp and display prerequisites before an expensive validation command.
2. S2-2. Guidance explains how to keep a working setup within the invocation, reuse existing allowed caches and discovery context, and revisit setup when relevant scripts, configuration, toolchain or workspace requirements change. It does not claim a successful setup remains valid after an unknown change.
3. S2-3. Every repair candidate still receives all required local project checks before publication. Existing clean and cache-bypass requirements stay intact. Reusing setup never means skipping checks or treating a previous candidate's local pass as current evidence.
4. S2-4. The agent explains setup retries and known environment problems in ordinary prose through current output. It uses existing failure and recovery behavior. No profile file, configuration key, schema, validator, readiness receipt, new port/service or additional blocking gate is added.
5. S2-5. Setup remains within the invocation's authority and resource ownership. Instructions do not permit automatic package installation, shared-daemon shutdown, changes to unrelated jobs, or reuse of another worktree's mutable state.

## Implementation guidance

Edit existing authored monitor/validation guidance and shared runtime repair briefings. Keep repository-specific details in existing repository guidance or scripts, and derive platform requirements from current manifests and CI. Do not add a fixed platform/tool list to the shared runtime or another environment-discovery mechanism.

The observed failures give concrete examples for SkillBill. Git and Python were missing in one container. Another had an unwritable Java user.home and could not create .skiko. Desktop validation needed Xvfb on Linux. Explain how the established repository/CI setup addresses those issues before the agent spends time on the full desktop build.

Prefer a known working host/setup over inventing a new container recipe each round. If isolation is required by the current instructions, reuse the established isolated setup rather than reconstructing it without cause. Describe an unavoidable setup failure with the current result vocabulary and actionable prose. Do not introduce a new prerequisite whose absence independently blocks a run.

Useful discovery context is a reminder to recheck the current instructions, not a trusted command-result cache. Changed requirements trigger fresh discovery and the existing validation process. No hashing protocol, catalog, probe record or completion receipt is required.

## Verification

Review the guidance against the missing-tools and unwritable-home examples, then a second repair round with unchanged setup. Confirm that it directs early checks and avoids needless reconstruction while retaining all required validation commands.

A prose-only guidance change needs no new tests. Extend an existing test only if necessary runtime behavior changes, and assert an observable regression rather than instruction text. No environment schema, validator, contract parity or cache framework tests are introduced.

Apply the parent architecture/ownership rules and ordinary later validation. This planning correction does not run checks or modify setup.

## Dependency notes

Depends on subtask 1's clearer existing progress guidance. Subtask 3 uses that reporting to distinguish setup work and merge resolution from CI-confirmed failed repairs.
