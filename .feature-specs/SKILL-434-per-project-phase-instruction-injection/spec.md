# SKILL-434 - per-project-phase-instruction-injection

## Mode

decomposed

## Intended Outcome

SKILL-434 Per-project phase instruction injection

Projects can add `.skill-bill/phases/<phase_id>.md` (for example `qa.md`, `implement.md`, `code_review.md`, `validate.md`, `pr.md`) holding project-specific instructions for that phase. Example: ZeroAccount's `.skill-bill/phases/qa.md` tells the QA agent to start api-simulator and set the environment to Local before QA starts.

Design decisions already made:
1. Location is `.skill-bill/phases/`. The installer's gitignore block adds `!.skill-bill/phases/` next to `!.skill-bill/config.yaml`, and `FeatureTaskRuntimeCheckpointScope` treats `.skill-bill/phases/` as trackable like `config.yaml`, so the files are committed and present in goal checkouts.
2. The shared phase prompt composer (`phasePromptMiddleSections` in runtime-engine `FeatureTaskRuntimePhasePromptComposerSections.kt`, whose inputs already carry `repoRoot`) injects the file content as a "Project instructions for this phase" section after the briefing, so it cannot override output or settlement contracts. It applies to every launch of that phase id, standalone (`phase qa`) and inside goal/feature runs, including retries and fix/repair attempts.
3. No file means no section. An unknown filename, an oversized file, or an unreadable file emits a `Warning:` line and an observability record, is skipped, and the run continues; it never blocks.
4. The phase activity record notes which instruction file was injected and its content hash.

Out of scope: runtime-executed `before_phase` commands.

## Overview

The work touches four seams that preplan located:

- Installer gitignore scaffold: `InstallApplyRepoLocalConfig.kt` in runtime-infra/skills (`appendGitignoreEntryIfAbsent`), plus the repository's own root `.gitignore`.
- Checkpoint scope: `FeatureTaskRuntimeCheckpointScope.isRuntimePrivatePath` in runtime-engine lifecycle/checkpoint.
- Prompt composition: `phasePromptMiddleSections` and `FeatureTaskRuntimePhasePromptComposeInputs` in runtime-engine phase/prompt/compose, fed by the single production call site `PhaseLaunchPreparation.composeLaunchPrompt`, which every launch (initial, retry, repair, standalone, goal/feature) passes through.
- Activity record: the per-launch briefing artifact `FeatureTaskRuntimePhaseLaunchBriefing` (wire map and `ALLOWED_FIELDS`), recorded by `recordLaunchBriefing`, plus `RuntimeDiagnostics`.

All four belong in one commit: each part is small, they share the launch-preparation seam, and none stands alone as a useful change. The bundle therefore has one subtask.

## Acceptance Criteria

1. `.skill-bill/phases/` files are trackable: the installer's gitignore scaffold and the repository root `.gitignore` un-ignore them next to `!.skill-bill/config.yaml`, and `FeatureTaskRuntimeCheckpointScope` treats paths under `.skill-bill/phases/` as trackable rather than runtime-private.
2. Every launch of a phase id (standalone and goal/feature runs, including retries and fix/repair attempts) whose `.skill-bill/phases/<phase_id>.md` exists gets a "Project instructions for this phase" prompt section placed after the briefing and before every retry, continuation, and output/settlement contract section.
3. A missing file adds no section and leaves the prompt unchanged; an unknown filename, oversized file, or unreadable file produces a `Warning:` diagnostics line and record, is skipped, and the launch continues without blocking.
4. The phase activity record for a launch names the injected instruction file and its SHA-256 content hash.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup.
- The project section must never displace or follow the output or settlement contract.
- Resolution of project instructions must never throw into launch preparation.

## Non-Goals

- Runtime-executed `before_phase` commands.
- Running any installer or install-refresh step; existing repositories pick up the gitignore lines on their next install.

## Validation Strategy

The validate phase runs the repository's required checks, including the tests listed in the subtask spec's Test Obligations, and verifies every acceptance criterion.

## Next Path

```bash
skill-bill goal SKILL-434
```
