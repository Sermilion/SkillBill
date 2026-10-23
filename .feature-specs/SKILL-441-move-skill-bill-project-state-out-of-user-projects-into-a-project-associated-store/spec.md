# SKILL-441 - move-skill-bill-project-state-out-of-user-projects-into-a-project-associated-store

## Mode

decomposed

## Intended Outcome

SKILL-441 Move skill-bill project state out of user projects into a project-associated store

Move all skill-bill project state out of user projects into a project-associated store under the skill-bill home, with zero footprint in the project.

## Goal
Projects must contain no skill-bill files. Today skill-bill writes `./.feature-specs/` (spec bundles, committed) and `./.skill-bill/` (config.yaml, run-evidence/, feature-task-tracking/, acceptance-repair/) into the user's repository. All of it moves to `~/.skill-bill/projects/<project-id>/`, which holds `config.yaml`, `specs/<issue>-<slug>/`, `run-evidence/`, `feature-task-tracking/`, `acceptance-repair/`, and `project.json` (recorded common-dir path, root commit, remote URL, last-seen time). Specs are tool artifacts and must not be committed.

## Project identity (zero footprint, reliable, never blocks)
No marker file anywhere in the project, including inside `.git`. Resolve in layers:
1. Exact: real path of `git rev-parse --git-common-dir`. All worktrees of a checkout resolve to the same project.
2. On miss, lineage: root commit hash(es) (`git rev-list --max-parents=0 HEAD`), remote URL as tie-breaker.
3. Candidate selection: exactly one candidate whose recorded path no longer exists -> adopt (moved/recloned), update path, emit record. Candidate path still exists -> second clone, new separate store, emit record noting the match. Multiple candidates -> new store, emit record, offer `skill-bill project link <id>` for manual attach.
4. Repo with no commits -> path key, re-keyed to lineage after first commit. Non-git directory -> hash of absolute path.
Every fallback emits a record per docs/observability-policy.md.

## Install lifecycle
Reinstall and full uninstall never delete `~/.skill-bill/projects/`. Both wipe paths in uninstall.sh (and SkillBillUninstallService) must skip it, enforced by a test.

## Agent access
The project store directory is added to `additionalWritableDirectories` for every agent launch so agents in the main checkout or any linked worktree read and write specs directly. Claude and Codex already honor `--add-dir`. Verify Cursor and Junie; a provider that cannot write outside the repo falls back to a staging copy in the checkout that the runtime promotes into the store at phase completion, emitting a warning record, never blocking. Plan prompts (AgentPlanStrategy spec bundle requirement) must name the store path instead of `.feature-specs/`.

## Single resolver
Replace every hardcoded `.feature-specs` / `.skill-bill` project path (about 26 main-source Kotlin files; duplicated constants FEATURE_SPEC_ROOT, SPECS_DIRECTORY, FEATURE_SPECS_DIRECTORY, GOAL_INTAKE_FEATURE_SPECS_MARKER; repo-local config path in FileSystemRepoLocalConfig; review SpecIntentProjectionResolver; GoalIntake/GoalIntakePreparation token parsing; decomposition manifest writer paths; worktree edit journal RUNTIME_PRIVATE_ROOT) with one project-store resolver. Manifest spec paths must resolve through the store, not by joining the repo root. The skill-bill skill content (Issue resolution and Local key allocation sections that scan `.feature-specs/`) must use the store as well.

## Legacy and backwards compatibility
- Goals that are in progress or blocked from `./.feature-specs/` at upgrade time finish in place as a backwards-compatibility layer.
- Any new launch whose intake is a spec under `./.feature-specs/` (or any old spec path) is treated as intake, not a plan: the runtime replans and writes a new bundle in the store. Never blocks.
- Existing `./.skill-bill/config.yaml` is copied into the store on first resolve with a warning record; afterwards the store copy wins.
- Existing run-evidence, feature-task-tracking, acceptance-repair are not migrated; the store starts fresh. The runtime warns once that the old `./.skill-bill/` can be deleted and never deletes user project files.

## Discoverability
- Desktop goal activity: once the plan phase finishes, the goal activity entry lists every file in the spec bundle (parent spec, subtask specs, decomposition manifest, and any other bundle files). Each is clickable and opens in the OS default application for that file type. The runtime supplies resolved absolute paths; the desktop does not join repo root with relative paths. Standalone plan run reports get the same clickable links.
- CLI: a command that prints the store path for the current project or an issue, and a way to edit the project config (e.g. `skill-bill config edit`).

## This repository
Untrack `.feature-specs/` and `.skill-bill/config.yaml` from SkillBill's own git.

## Accepted trade-offs
Specs and config are no longer shared via git or visible to PR reviewers; `phase:pr` should include a spec summary in the PR description. Deleting `~/.skill-bill/projects/` loses specs.

## Overview

SKILL-441 Move skill-bill project state out of user projects into a project-associated store

Move all skill-bill project state out of user projects into a project-associated store under the skill-bill home, with zero footprint in the project.

## Goal
Projects must contain no skill-bill files. Today skill-bill writes `./.feature-specs/` (spec bundles, committed) and `./.skill-bill/` (config.yaml, run-evidence/, feature-task-tracking/, acceptance-repair/) into the user's repository. All of it moves to `~/.skill-bill/projects/<project-id>/`, which holds `config.yaml`, `specs/<issue>-<slug>/`, `run-evidence/`, `feature-task-tracking/`, `acceptance-repair/`, and `project.json` (recorded common-dir path, root commit, remote URL, last-seen time). Specs are tool artifacts and must not be committed.

## Project identity (zero footprint, reliable, never blocks)
No marker file anywhere in the project, including inside `.git`. Resolve in layers:
1. Exact: real path of `git rev-parse --git-common-dir`. All worktrees of a checkout resolve to the same project.
2. On miss, lineage: root commit hash(es) (`git rev-list --max-parents=0 HEAD`), remote URL as tie-breaker.
3. Candidate selection: exactly one candidate whose recorded path no longer exists -> adopt (moved/recloned), update path, emit record. Candidate path still exists -> second clone, new separate store, emit record noting the match. Multiple candidates -> new store, emit record, offer `skill-bill project link <id>` for manual attach.
4. Repo with no commits -> path key, re-keyed to lineage after first commit. Non-git directory -> hash of absolute path.
Every fallback emits a record per docs/observability-policy.md.

## Install lifecycle
Reinstall and full uninstall never delete `~/.skill-bill/projects/`. Both wipe paths in uninstall.sh (and SkillBillUninstallService) must skip it, enforced by a test.

## Agent access
The project store directory is added to `additionalWritableDirectories` for every agent launch so agents in the main checkout or any linked worktree read and write specs directly. Claude and Codex already honor `--add-dir`. Verify Cursor and Junie; a provider that cannot write outside the repo falls back to a staging copy in the checkout that the runtime promotes into the store at phase completion, emitting a warning record, never blocking. Plan prompts (AgentPlanStrategy spec bundle requirement) must name the store path instead of `.feature-specs/`.

## Single resolver
Replace every hardcoded `.feature-specs` / `.skill-bill` project path (about 26 main-source Kotlin files; duplicated constants FEATURE_SPEC_ROOT, SPECS_DIRECTORY, FEATURE_SPECS_DIRECTORY, GOAL_INTAKE_FEATURE_SPECS_MARKER; repo-local config path in FileSystemRepoLocalConfig; review SpecIntentProjectionResolver; GoalIntake/GoalIntakePreparation token parsing; decomposition manifest writer paths; worktree edit journal RUNTIME_PRIVATE_ROOT) with one project-store resolver. Manifest spec paths must resolve through the store, not by joining the repo root. The skill-bill skill content (Issue resolution and Local key allocation sections that scan `.feature-specs/`) must use the store as well.

## Legacy and backwards compatibility
- Goals that are in progress or blocked from `./.feature-specs/` at upgrade time finish in place as a backwards-compatibility layer.
- Any new launch whose intake is a spec under `./.feature-specs/` (or any old spec path) is treated as intake, not a plan: the runtime replans and writes a new bundle in the store. Never blocks.
- Existing `./.skill-bill/config.yaml` is copied into the store on first resolve with a warning record; afterwards the store copy wins.
- Existing run-evidence, feature-task-tracking, acceptance-repair are not migrated; the store starts fresh. The runtime warns once that the old `./.skill-bill/` can be deleted and never deletes user project files.

## Discoverability
- Desktop goal activity: once the plan phase finishes, the goal activity entry lists every file in the spec bundle (parent spec, subtask specs, decomposition manifest, and any other bundle files). Each is clickable and opens in the OS default application for that file type. The runtime supplies resolved absolute paths; the desktop does not join repo root with relative paths. Standalone plan run reports get the same clickable links.
- CLI: a command that prints the store path for the current project or an issue, and a way to edit the project config (e.g. `skill-bill config edit`).

## This repository
Untrack `.feature-specs/` and `.skill-bill/config.yaml` from SkillBill's own git.

## Accepted trade-offs
Specs and config are no longer shared via git or visible to PR reviewers; `phase:pr` should include a spec summary in the PR description. Deleting `~/.skill-bill/projects/` loses specs.

## Acceptance Criteria

1. SKILL-441 Move skill-bill project state out of user projects into a project-associated store Move all skill-bill project state out of user projects into a project-associated store under the skill-bill home, with zero footprint in the project. ## Goal Projects must contain no skill-bill files. Today skill-bill writes `./.feature-specs/` (spec bundles, committed) and `./.skill-bill/` (config.yaml, run-evidence/, feature-task-tracking/, acceptance-repair/) into the user's repository. All of it moves to `~/.skill-bill/projects/<project-id>/`, which holds `config.yaml`, `specs/<issue>-<slug>/`, `run-evidence/`, `feature-task-tracking/`, `acceptance-repair/`, and `project.json` (recorded common-dir path, root commit, remote URL, last-seen time). Specs are tool artifacts and must not be committed. ## Project identity (zero footprint, reliable, never blocks) No marker file anywhere in the project, including inside `.git`. Resolve in layers: 1. Exact: real path of `git rev-parse --git-common-dir`. All worktrees of a checkout resolve to the same project. 2. On miss, lineage: root commit hash(es) (`git rev-list --max-parents=0 HEAD`), remote URL as tie-breaker. 3. Candidate selection: exactly one candidate whose recorded path no longer exists -> adopt (moved/recloned), update path, emit record. Candidate path still exists -> second clone, new separate store, emit record noting the match. Multiple candidates -> new store, emit record, offer `skill-bill project link <id>` for manual attach. 4. Repo with no commits -> path key, re-keyed to lineage after first commit. Non-git directory -> hash of absolute path. Every fallback emits a record per docs/observability-policy.md. ## Install lifecycle Reinstall and full uninstall never delete `~/.skill-bill/projects/`. Both wipe paths in uninstall.sh (and SkillBillUninstallService) must skip it, enforced by a test. ## Agent access The project store directory is added to `additionalWritableDirectories` for every agent launch so agents in the main checkout or any linked worktree read and write specs directly. Claude and Codex already honor `--add-dir`. Verify Cursor and Junie; a provider that cannot write outside the repo falls back to a staging copy in the checkout that the runtime promotes into the store at phase completion, emitting a warning record, never blocking. Plan prompts (AgentPlanStrategy spec bundle requirement) must name the store path instead of `.feature-specs/`. ## Single resolver Replace every hardcoded `.feature-specs` / `.skill-bill` project path (about 26 main-source Kotlin files; duplicated constants FEATURE_SPEC_ROOT, SPECS_DIRECTORY, FEATURE_SPECS_DIRECTORY, GOAL_INTAKE_FEATURE_SPECS_MARKER; repo-local config path in FileSystemRepoLocalConfig; review SpecIntentProjectionResolver; GoalIntake/GoalIntakePreparation token parsing; decomposition manifest writer paths; worktree edit journal RUNTIME_PRIVATE_ROOT) with one project-store resolver. Manifest spec paths must resolve through the store, not by joining the repo root. The skill-bill skill content (Issue resolution and Local key allocation sections that scan `.feature-specs/`) must use the store as well. ## Legacy and backwards compatibility - Goals that are in progress or blocked from `./.feature-specs/` at upgrade time finish in place as a backwards-compatibility layer. - Any new launch whose intake is a spec under `./.feature-specs/` (or any old spec path) is treated as intake, not a plan: the runtime replans and writes a new bundle in the store. Never blocks. - Existing `./.skill-bill/config.yaml` is copied into the store on first resolve with a warning record; afterwards the store copy wins. - Existing run-evidence, feature-task-tracking, acceptance-repair are not migrated; the store starts fresh. The runtime warns once that the old `./.skill-bill/` can be deleted and never deletes user project files. ## Discoverability - Desktop goal activity: once the plan phase finishes, the goal activity entry lists every file in the spec bundle (parent spec, subtask specs, decomposition manifest, and any other bundle files). Each is clickable and opens in the OS default application for that file type. The runtime supplies resolved absolute paths; the desktop does not join repo root with relative paths. Standalone plan run reports get the same clickable links. - CLI: a command that prints the store path for the current project or an issue, and a way to edit the project config (e.g. `skill-bill config edit`). ## This repository Untrack `.feature-specs/` and `.skill-bill/config.yaml` from SkillBill's own git. ## Accepted trade-offs Specs and config are no longer shared via git or visible to PR reviewers; `phase:pr` should include a spec summary in the PR description. Deleting `~/.skill-bill/projects/` loses specs.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Non-Goals

- None

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.
